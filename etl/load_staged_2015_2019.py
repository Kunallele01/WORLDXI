"""
Loads the staged 2014/15-2018/19 season files into Supabase.

DRY RUN BY DEFAULT. Pass --apply to write. Nothing here was run on the night
the files were staged — the user asked for data gathering only.

RUN verify_staged_2015_2019.py FIRST. This script re-checks the cheap
invariants but does not repeat the historical checks, and loading a bad
staging file is far more expensive to undo than to prevent.

IDEMPOTENCY. The original loader is explicitly not idempotent: re-running it
inserts a second copy of everything. This one refuses to touch a league-season
that already has a `seasons` row, so a half-finished run is resumed by season
rather than by truncating. Check what exists before forcing anything.

ORDER MATTERS. seasons -> club_seasons -> players -> player_season_stats ->
fixtures. Fixtures reference club_season ids, so they come last.

AFTERWARDS, two things are still missing and the draft needs both:
  * club_seasons.squad_strength is left NULL here (it is derived from the
    players, which do not exist until this script has run). Run
    backfill_squad_strength.py once this finishes.
  * the draft pool reads strength_quartile from club_season_draft_pool, which
    is computed off squad_strength, so the new seasons stay invisible to the
    spin until that backfill lands.
"""
from __future__ import annotations

import csv
import os
import sys
from collections import defaultdict
from pathlib import Path

import requests
from dotenv import load_dotenv

APPLY = "--apply" in sys.argv
HERE = Path(__file__).parent
STAGED = HERE / "staged"

load_dotenv(HERE / ".env")
SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
    "Prefer": "return=representation",
}
BATCH = 200

LEAGUES = {"PL": "Premier League", "LaLiga": "La Liga"}
# Positional args `FIRST LAST` pick another range (e.g. `2009 2013` for
# 2009/10-2013/14); with none, the original 2014/15-2018/19 run. `--apply`
# still has to be passed separately to write anything.
_YEARS = [a for a in sys.argv[1:] if a.isdigit()]
_FIRST, _LAST = (int(_YEARS[0]), int(_YEARS[1])) if len(_YEARS) == 2 else (2014, 2018)
SEASONS = [f"{y}/{str(y + 1)[2:]}" for y in range(_FIRST, _LAST + 1)]
# Tier B for 2014/15 onwards: every season carries Understat xG, shots, keeper
# numbers and a rating, well past the spec's Tier C. Nothing in the app reads
# this column, but it should still be true — so seasons before 2014/15, whose
# npxg is a documented proxy with no real xG behind it, are Tier C.
DATA_TIER = "B" if _FIRST >= 2014 else "C"

# The FBref archive spells a few clubs differently from the names already in
# the database, which split Betis and Newcastle into two clubs once before
# (fixed by hand 2026-09-06). Applied HERE, at load, to tables, players and
# fixtures alike, so the staged files stay faithful to their source and the
# database gets one name per club.
CLUB_RENAMES = {
    "Betis": "Real Betis",
    "Newcastle Utd": "Newcastle",
    "Racing Sant": "Racing Santander",
}


def club(name: str) -> str:
    return CLUB_RENAMES.get(name, name)

SLOTS = ["cb", "fb", "dm", "cm", "cam", "winger", "st"]
INT_COLS = ["appearances", "minutes", "goals", "assists", "yellow_cards",
            "red_cards", "shots", "shots_on_target", "key_passes", "tackles",
            "interceptions", "saves", "clean_sheets", "overall_rating",
            "finishing", "creation", "carrying", "buildup", "defense",
            "physical"] + [f"pos_rating_{s}" for s in SLOTS]
FLOAT_COLS = ["save_pct", "xg", "xa", "npxg"]


def rest(method: str, path: str, **kwargs):
    r = requests.request(method, f"{SUPABASE_URL}/rest/v1/{path}", headers=HEADERS,
                         timeout=180, **kwargs)
    if not r.ok:
        raise RuntimeError(f"{method} {path} -> {r.status_code}: {r.text[:400]}")
    return r.json() if r.content else None


def read(prefix: str, league: str, season: str) -> list[dict]:
    tag = f"{league}_{season.replace('/', '-')}"
    with open(STAGED / f"{prefix}_{tag}.tsv", encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def num(v, cast):
    if v is None or v == "":
        return None
    try:
        return cast(float(v))
    except (TypeError, ValueError):
        return None


def load_season(league_key: str, league_id: int, season: str) -> None:
    label_seen = rest("GET", "seasons", params={
        "league_id": f"eq.{league_id}", "label": f"eq.{season}", "select": "id",
    })
    if label_seen:
        print(f"   SKIP {LEAGUES[league_key]} {season}: already present (season id "
              f"{label_seen[0]['id']}). Delete it first if you mean to reload.")
        return

    table = read("table", league_key, season)
    players = read("playerseasons", league_key, season)
    fixtures = read("fixtures", league_key, season)
    assert len(table) == 20, f"{season}: {len(table)} clubs"
    assert len(fixtures) == 380, f"{season}: {len(fixtures)} fixtures"

    if not APPLY:
        idents = {(p["full_name"], p["birth_year"]) for p in players}
        print(f"   would load {LEAGUES[league_key]} {season}: 1 season, {len(table)} clubs, "
              f"{len(players)} player-seasons ({len(idents)} identities), {len(fixtures)} fixtures")
        return

    season_row = rest("POST", "seasons", json=[{
        "league_id": league_id, "label": season,
        "start_year": int(season[:4]), "data_tier": DATA_TIER,
    }])[0]
    season_id = season_row["id"]

    club_rows = rest("POST", "club_seasons", json=[{
        "season_id": season_id, "club_name": club(t["club_name"]),
        "final_position": int(t["final_position"]),
        "wins": int(t["wins"]), "draws": int(t["draws"]), "losses": int(t["losses"]),
        "goals_for": int(t["goals_for"]), "goals_against": int(t["goals_against"]),
        "points": int(t["points"]),
        # squad_strength is derived from players that do not exist yet.
        # backfill_squad_strength.py fills it; until then the draft pool
        # cannot see this season.
    } for t in table])
    club_id = {c["club_name"]: c["id"] for c in club_rows}

    # Player identities are shared across seasons: a man who played 2016/17 and
    # 2017/18 is ONE row. Keyed on (full_name, birth_year) — never name alone,
    # which conflates the two Rodris.
    idents = sorted({(p["full_name"], int(p["birth_year"])) for p in players})
    existing: dict[tuple[str, int], int] = {}
    names = sorted({n for n, _ in idents})
    for i in range(0, len(names), 100):
        chunk = names[i:i + 100]
        quoted = ",".join('"' + n.replace('"', '""') + '"' for n in chunk)
        for row in rest("GET", "players", params={
            "full_name": f"in.({quoted})", "select": "id,full_name,date_of_birth",
        }):
            if row["date_of_birth"]:
                existing[(row["full_name"], int(row["date_of_birth"][:4]))] = row["id"]

    fresh = [i for i in idents if i not in existing]
    for i in range(0, len(fresh), BATCH):
        chunk = fresh[i:i + BATCH]
        made = rest("POST", "players", json=[
            {"full_name": n, "date_of_birth": f"{by}-01-01"} for n, by in chunk
        ])
        for row, ident in zip(made, chunk):
            existing[ident] = row["id"]

    stat_rows = []
    for p in players:
        row = {
            "player_id": existing[(p["full_name"], int(p["birth_year"]))],
            "club_season_id": club_id[club(p["club_name"])],
            "primary_position": p["primary_position"],
            "secondary_positions": [s for s in p["secondary_positions"].split(";") if s],
            "position_side": p["position_side"] or None,
            "pos_rating_edition": p["pos_rating_edition"] or None,
            "stats_source": p["stats_source"],
        }
        for c in INT_COLS:
            row[c] = num(p.get(c), int)
        for c in FLOAT_COLS:
            row[c] = num(p.get(c), float)
        stat_rows.append(row)
    for i in range(0, len(stat_rows), BATCH):
        rest("POST", "player_season_stats", json=stat_rows[i:i + BATCH])

    fixture_rows = [{
        "season_id": season_id,
        "matchday": int(f["matchday"]) or None,
        "home_club_season_id": club_id[club(f["home"])],
        "away_club_season_id": club_id[club(f["away"])],
        "home_goals": int(f["home_goals"]),
        "away_goals": int(f["away_goals"]),
    } for f in fixtures]
    for i in range(0, len(fixture_rows), BATCH):
        rest("POST", "fixtures", json=fixture_rows[i:i + BATCH])

    print(f"   loaded {LEAGUES[league_key]} {season}: season {season_id}, "
          f"{len(club_rows)} clubs, {len(stat_rows)} player-seasons "
          f"({len(fresh)} new identities), {len(fixture_rows)} fixtures")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    leagues = {l["name"]: l["id"] for l in rest("GET", "leagues", params={"select": "id,name"})}
    print("DRY RUN — pass --apply to write.\n" if not APPLY else "WRITING.\n")
    for key, name in LEAGUES.items():
        if name not in leagues:
            raise SystemExit(f"league {name!r} missing from the database")
        for season in SEASONS:
            load_season(key, leagues[name], season)
    if APPLY:
        print("\nNOW RUN: python backfill_squad_strength.py --apply")
        print("Until it does, the new seasons are invisible to the draft spin.")


if __name__ == "__main__":
    main()
