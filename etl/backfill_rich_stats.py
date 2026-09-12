"""
Backfills the per-player stats that make every position simulable.

TWO TIERS, AND THE ASYMMETRY IS THE POINT
-----------------------------------------
  fbref_archive  2019/20-2021/22  the full rich set: clearances, blocks,
                                  aerials, dribbles, carries, pass %, touches,
                                  SCA/GCA, and post-shot xG for keepers
  understat      all five seasons xG, npxG, xA, key passes

Understat is the floor because it is the only source covering every season. A
match engine must read ONLY columns that exist in both tiers, or a 2023/24
player loses to a 2021/22 player for having thinner data rather than for being
worse — the one failure mode that would discredit a result. `stats_source`
records which tier each row got so that rule can be checked rather than
remembered.

MATCHING. Same discipline as the FIFA anchor: identity is (name, birth year),
never name alone, and every tier requires uniqueness. Understat publishes no
birth year, so it matches on (name, season) with club agreement as the
disambiguator; a player who moved mid-season appears there with a comma-joined
team ("Chelsea,Leicester"), so any of his clubs counts as agreement.

Dry-run by default; pass --apply to write.
"""
from __future__ import annotations

import csv
import os
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from dotenv import load_dotenv

import fbref_archive
from fifa_lookup import clubs_agree
from roles import _normalize

APPLY = "--apply" in sys.argv
sys.stdout.reconfigure(encoding="utf-8")

load_dotenv(Path(__file__).parent / ".env")
SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
}
UNDERSTAT_TSV = Path(__file__).parent / "understat" / "understat_players.tsv"


def fetch(table: str, params: dict) -> list[dict]:
    out: list[dict] = []
    offset = 0
    with requests.Session() as s:
        s.headers.update(HEADERS)
        while True:
            p = dict(params, limit=1000, offset=offset)
            r = s.get(f"{SUPABASE_URL}/rest/v1/{table}", params=p, timeout=180)
            r.raise_for_status()
            batch = r.json()
            if not batch:
                return out
            out += batch
            offset += 1000


#: Letters that NFKD does NOT decompose, so the shared normaliser strips them
#: to nothing rather than folding them. "Lukasz Fabianski" with a barred L
#: became "ukasz fabianski" and matched nobody. Folded here rather than in
#: roles._normalize because that function also keys the FIFA anchor, and
#: changing it would silently reshuffle 5,659 existing rating matches.
_UNDECOMPOSED = str.maketrans({
    "ł": "l", "Ł": "L",   # l with stroke
    "ð": "d", "Ð": "D",   # eth
    "þ": "th", "Þ": "Th", # thorn
    "ø": "o", "Ø": "O",   # o with stroke
    "đ": "d", "Đ": "D",   # d with stroke
    "æ": "ae", "Æ": "Ae",
    "œ": "oe", "Œ": "Oe",
    "ß": "ss",
})


def norm(name: str) -> str:
    return _normalize(name.translate(_UNDECOMPOSED))


def _num(v):
    """Archive cells are numpy floats with NaN for missing."""
    if v is None or v != v:
        return None
    return v


def _int(v):
    n = _num(v)
    return int(n) if n is not None else None


#: Columns typed `integer` in Postgres. The archive hands back numpy floats for
#: every count — 12.0, not 12 — and PostgREST refuses those against an integer
#: column ("invalid input syntax for type integer: 12.0"). Everything not
#: listed here is `numeric` and keeps its decimals.
INT_COLUMNS = {
    "key_passes", "progressive_passes", "dribbles_completed", "progressive_carries",
    "touches", "interceptions", "clearances", "blocks", "errors",
    "aerial_won", "aerial_lost", "recoveries",
    "shot_creating_actions", "goal_creating_actions", "sweeper_actions",
}


def coerce(col: str, value):
    """Postgres-safe value for `col`, or None when the source cell was blank."""
    n = _num(value)
    if n is None:
        return None
    return int(round(n)) if col in INT_COLUMNS else round(float(n), 3)


print("Loading our players...")
players = {p["id"]: p for p in fetch("players", {"select": "id,full_name,date_of_birth"})}
club_seasons = {c["id"]: c for c in fetch("club_seasons", {"select": "id,club_name,season_id"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select": "id,player_id,club_season_id,minutes,primary_position"})
print(f"  {len(stats)} player-seasons")

# ---------------------------------------------------------------- our index
# (normalised name, season) -> rows, so a source without birth years can still
# find a unique match, and one with them can narrow further.
ours_by_name_season = defaultdict(list)
for s in stats:
    p = players[s["player_id"]]
    cs = club_seasons[s["club_season_id"]]
    label = seasons[cs["season_id"]]
    dob = p.get("date_of_birth") or ""
    entry = {
        "stat_id": s["id"],
        "name": p["full_name"],
        "norm": norm(p["full_name"]),
        "tokens": frozenset(norm(p["full_name"]).split()),
        "birth_year": int(dob[:4]) if len(dob) >= 4 else None,
        "club": cs["club_name"],
        "label": label,
        "minutes": s["minutes"] or 0,
        "position": s["primary_position"],
    }
    ours_by_name_season[(entry["norm"], label)].append(entry)

all_by_season = defaultdict(list)
for (_name_key, label), entries in ours_by_name_season.items():
    all_by_season[label].extend(entries)


def resolve(name: str, label: str, clubs: list[str], birth_year: int | None) -> dict | None:
    """
    One of our player-seasons, or None. Exact normalised name first, then a
    bidirectional token-subset match, each narrowed by birth year and club and
    each requiring uniqueness. The subset tier is what handles the two real
    name-form families in this data: a source shortening a name ("Ederson" for
    "Ederson Moraes") and a source lengthening it ("Idrissa Gana Gueye" for
    "Idrissa Gueye").
    """
    n = norm(name)
    tokens = frozenset(n.split())
    if not tokens:
        return None

    def narrow(cands: list[dict]) -> dict | None:
        if len(cands) == 1:
            return cands[0]
        if birth_year is not None:
            by = [c for c in cands if c["birth_year"] in (birth_year, birth_year - 1, birth_year + 1)]
            if len(by) == 1:
                return by[0]
            cands = by or cands
        if clubs:
            cl = [c for c in cands if any(clubs_agree(c["club"], k) for k in clubs)]
            if len(cl) == 1:
                return cl[0]
            cands = cl or cands
        return cands[0] if len(cands) == 1 else None

    hit = narrow(list(ours_by_name_season.get((n, label), [])))
    if hit:
        return hit

    subset = [e for e in all_by_season.get(label, ()) if tokens <= e["tokens"] or e["tokens"] <= tokens]
    hit = narrow(subset)
    if hit:
        return hit

    # Surname plus club, guarded by uniqueness within that club-season. This is
    # the tier that handles SHORT FORMS, which neither exact nor subset
    # matching can reach because the tokens genuinely differ: Understat says
    # "Andrew Robertson" where we hold "Andy Robertson", and "Joseph Gomez"
    # where we hold "Joe Gomez". Uniqueness within one squad is a strong
    # guard — a club rarely has two players sharing a surname.
    if not clubs:
        return None
    surname = n.split()[-1]
    same_club = [
        e for e in all_by_season.get(label, ())
        if e["tokens"] and list(e["norm"].split())[-1] == surname
        and any(clubs_agree(e["club"], k) for k in clubs)
    ]
    return same_club[0] if len(same_club) == 1 else None


# ------------------------------------------------------- tier 1: the archive
print("\nLoading the pre-strip FBref archive (2019/20-2021/22)...")
arch = {t: fbref_archive.load(t) for t in
        ("passing", "possession", "defense", "misc", "gca", "keepers_adv")}
for t, df in arch.items():
    print(f"  {t:12} {len(df)} rows")

updates: dict[int, dict] = defaultdict(dict)
tiers = Counter()


def archive_key(row: dict):
    return (str(row["Player"]), fbref_archive.SEASON_LABELS[int(row["Season_End_Year"])],
            [str(row["Squad"])], _int(row["Born"]))


def apply_archive(table: str, mapping: dict):
    """mapping: our column -> archive attribute name."""
    matched = 0
    # Rows as DICTS, not namedtuples. itertuples() silently renames any column
    # that is not a valid Python identifier — "#OPA_Sweeper" became "_31" — so
    # getattr returned None for every row and the column filled with nothing,
    # with no error anywhere. Dict access uses the real names.
    for row in arch[table].to_dict("records"):
        name, label, clubs, born = archive_key(row)
        hit = resolve(name, label, clubs, born)
        if not hit:
            tiers[f"{table}: unmatched"] += 1
            continue
        matched += 1
        payload = updates[hit["stat_id"]]
        for col, attr in mapping.items():
            val = coerce(col, row.get(attr))
            if val is not None:
                payload[col] = val
        payload["stats_source"] = "fbref_archive"
    tiers[f"{table}: matched"] += matched


apply_archive("passing", {
    "pass_completion_pct": "Cmp_percent_Total",
    "key_passes": "KP",
    "progressive_passes": "Prog",
})
apply_archive("possession", {
    "dribbles_completed": "Succ_Dribbles",
    "progressive_carries": "Prog_Carries",
    "touches": "Touches_Touches",
})
apply_archive("defense", {
    "interceptions": "Int",
    "clearances": "Clr",
    "blocks": "Blocks_Blocks",
    "errors": "Err",
})
apply_archive("misc", {
    "aerial_won": "Won_Aerial",
    "aerial_lost": "Lost_Aerial",
    "recoveries": "Recov",
})
apply_archive("gca", {
    "shot_creating_actions": "SCA_SCA",
    "goal_creating_actions": "GCA_GCA",
})
apply_archive("keepers_adv", {
    "psxg": "PSxG_Expected",
    "crosses_stopped_pct": "Stp_percent_Crosses",
    "sweeper_actions": "#OPA_Sweeper",
})

# psxg_prevented is derived rather than read: the archive publishes it under a
# mangled column name ("PSxG+_per__minus__Expected") that is easy to misread,
# and PSxG minus goals conceded is unambiguous.
for row in arch["keepers_adv"].to_dict("records"):
    name, label, clubs, born = archive_key(row)
    hit = resolve(name, label, clubs, born)
    psxg, ga = _num(row.get("PSxG_Expected")), _num(row.get("GA_Goals"))
    if hit and psxg is not None and ga is not None:
        updates[hit["stat_id"]]["psxg_prevented"] = round(psxg - ga, 2)

# ----------------------------------------------------- tier 2: understat xG
print("\nLoading Understat (all five seasons)...")
us_rows = list(csv.DictReader(open(UNDERSTAT_TSV, encoding="utf-8"), delimiter="\t"))
print(f"  {len(us_rows)} player-seasons")

us_matched = us_missed = us_split = 0
missed_regulars = []
for r in us_rows:
    clubs = [c.strip() for c in r["team_title"].split(",") if c.strip()]

    # A mid-season transfer is AMBIGUOUS BY CONSTRUCTION, not a match failure.
    # Understat reports one combined row per player-season ("Southampton,
    # Arsenal") while we hold one row per club, so a single set of totals maps
    # onto two of our rows and cannot be split honestly. Apportioning by
    # minutes would assume the player performed at the same rate at both clubs,
    # which is exactly what a mid-season move usually disproves. These are
    # skipped and counted rather than approximated.
    if len(clubs) > 1:
        us_split += 1
        continue

    hit = resolve(r["player_name"], r["season"], clubs, None)
    if not hit:
        us_missed += 1
        if int(r["time"] or 0) >= 900:
            missed_regulars.append((r["player_name"], r["season"], r["time"]))
        continue
    us_matched += 1
    payload = updates[hit["stat_id"]]
    for col, key in (("xg", "xG"), ("xa", "xA"), ("npxg", "npxG")):
        try:
            payload[col] = round(float(r[key]), 3)
        except (TypeError, ValueError):
            pass
    try:
        # Only when the archive has not already supplied it — the archive is
        # the richer tier and must win on any column both sources carry.
        payload.setdefault("key_passes", int(r["key_passes"]))
    except (TypeError, ValueError):
        pass
    # Understat is the FLOOR, so it must never downgrade a row that already
    # got the richer archive tier.
    payload.setdefault("stats_source", "understat")

print(f"  matched {us_matched}, unmatched {us_missed} "
      f"(regulars >=900min: {len(missed_regulars)})")
print(f"  skipped as mid-season transfers (combined totals, cannot split): {us_split}")
for m in missed_regulars[:10]:
    print(f"     {m[0]:30} {m[1]}  {m[2]} min")

# ------------------------------------------------------------------- report
print("\narchive match tiers:")
for k, v in sorted(tiers.items()):
    print(f"   {k:28} {v}")
print(f"\nrows to update: {len(updates)} of {len(stats)}")
by_source = Counter(p.get("stats_source") for p in updates.values())
print(f"   by tier: {dict(by_source)}")
sample = list(updates.items())[:2]
for sid, payload in sample:
    print(f"\n   stat {sid}: {payload}")

if not APPLY:
    print("\nDRY RUN — pass --apply to write.")
    raise SystemExit(0)


def patch(item):
    stat_id, payload = item
    with requests.Session() as s:
        s.headers.update(HEADERS)
        r = s.patch(f"{SUPABASE_URL}/rest/v1/player_season_stats",
                    params={"id": f"eq.{stat_id}"}, json=payload, timeout=90)
        r.raise_for_status()


print(f"\nWriting {len(updates)} rows...")
with ThreadPoolExecutor(max_workers=16) as pool:
    list(pool.map(patch, updates.items()))
print("Done.")
