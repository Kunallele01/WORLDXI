"""
Builds a unified per-season FIFA/EA-FC quality-rating table from the four
downloaded edition CSVs, and matches it against the players already loaded
in Supabase.

Why an external anchor at all: see attributes.py's module docstring. Short
version, verified empirically across all 10 loaded seasons — defensive
"volume" stats are INVERSELY correlated with defender quality (CBs at the
top-5 defenses average 1.88 tackles+interceptions per 90; CBs at the
bottom-5 average 2.17). No reweighting of box-score stats can produce a
sane centre-back rating, so quality has to come from somewhere else and the
stats become a season-performance modifier on top of it.

Edition -> season mapping. Each edition ships in September and is rated on
what players had JUST DONE, so edition N+1 is the best description of season
N — not edition N, which was set before that season kicked off:

    FIFA 21 -> 2019/20   FIFA 22 -> 2020/21   FIFA 23 -> 2021/22
    EA FC 24 -> 2022/23  EA FC 25 -> 2023/24

MAPPING CORRECTED 2026-08-29 (was off by one). The original mapping paired
edition N with season N, on the reasoning that a September release describes
"a player entering that season". True of the release date, false of the
ratings: EA grades on the previous campaign. Measured, correlation of squad
strength against actual final league position:

    season played   same-year edition   NEXT-year edition
    2019/20              -0.665              -0.852
    2020/21              -0.796              -0.904
    2021/22              -0.812              -0.901
    2022/23              -0.615              -0.824
    ALL                  -0.737              -0.871

Every season, without exception. The user-visible symptom that exposed it:
Real Madrid's 2021/22 double-winning side scored 84.07, the LOWEST of their
five seasons, because FIFA 22 was set in Sept 2021 right after they finished
2nd and lost Ramos and Varane. Under the corrected mapping it scores 85.86.
Per-player this is also the more defensible reading — Benzema's post-Ballon
d'Or FIFA 23 rating belongs to the 2021/22 season that earned it.

SOURCE (2026-08-29, second pass): the single canonical Kaggle file
`archive/male_players.csv` — stefanoleone992's "EA Sports FC 24 complete
player dataset", which carries every edition FIFA 15..FC 24 in one table
keyed by `fifa_version`, with `long_name`, `dob` and `overall` populated
throughout.

This REPLACED a first pass that stitched four separate CSVs together from
whatever GitHub mirrors were reachable. Two of those (FIFA 21 and FIFA 23)
turned out to be non-canonical dumps with ZERO long_name and ZERO dob —
short names like "L. Dunk" and an age, nothing else. That caused two real
failures worth remembering:
  - identity for those editions could only be recovered by joining
    sofifa_id back to the two good files, so any player absent from those
    was unresolvable;
  - the FIFA 23 dump was also simply incomplete. Lewis Dunk (Brighton
    captain, 3239 minutes) was missing from it entirely, and a loosened
    surname matcher silently bound him to Harrison Dunk (rated 64).
Lesson: verify a data source actually contains the identity fields you
depend on before building matching logic on top of it.
"""
from __future__ import annotations

import csv
from collections import Counter
import sys
from pathlib import Path

from roles import _normalize

# EA publishes a rating for every player at every position. These are the
# columns behind the out-of-position model (see migration_0005): rather than
# inventing a penalty, we read what EA already decided each individual player
# is worth in each role.
#
# Left/right variants are collapsed with max() on purpose — EA's `lb` and `rb`
# columns are IDENTICAL for every player in the dataset, so it models side as
# identity rather than ability, and keeping both would imply a distinction the
# source does not make.
SLOT_COLUMNS = {
    "cb": ["cb"],
    "fb": ["lb", "rb"],
    "dm": ["cdm"],
    "cm": ["cm"],
    "cam": ["cam"],
    "winger": ["lw", "rw"],
    "st": ["st"],
}
SLOT_KEYS = list(SLOT_COLUMNS)

FIFA_DIR = Path(__file__).parent / "fifa"
SOURCE = Path(__file__).parent / "archive" / "male_players.csv"

# Editions kept in the table. Rows are keyed by EDITION, not by season: a
# season resolves to a preferred edition and a fallback one, and that choice
# belongs in the lookup (see fifa_lookup.SEASON_EDITIONS), not baked in here.
#
# FIFA 20 is retained purely as the fallback for 2019/20. Keying rows by
# season directly — the first version of this shift — silently dropped every
# player who played a season and then left top-flight football before the
# next edition shipped: Mariappa, Garay, Diego Costa, Lingard and ~50 other
# regulars lost their anchor entirely and fell through to stats-only ratings
# in the 30s and 40s, which is nonsense for a top-flight starter.
KEPT_EDITIONS = {"15", "16", "17", "18", "19", "20", "21", "22", "23", "24"}
# 16-19 added 2026-09-05 for the 2014/15-2018/19 expansion. Same rule as
# above: edition N+1 describes season N, so 2014/15 needs FIFA 16 and
# 2018/19 needs FIFA 20 — which was already kept as 2019/20's fallback.
# FIFA 15 is kept ONLY as 2014/15's fallback edition, exactly as FIFA 20
# serves 2019/20. It is never a primary anchor: 2013/14 is not being loaded,
# because no xG exists for it at any source.

# EA FC 25 (describes 2023/24) is not in the canonical archive, which stops at
# FC 24. It comes from a separate, weaker export: short names and an age, no
# long_name and no dob. That costs coverage (~81% vs ~94%), so it is loaded
# through its own reader rather than pretending it has the same schema.
FC25_SOURCE = Path(__file__).parent / "29 Aug Files" / "male_players.csv"
FC25_EDITION = "25"
FC25_EDITION_YEAR = 2024  # ships Sept 2024, so age -> birth year = 2024 - age

csv.field_size_limit(10_000_000)


def _int(v: str) -> str:
    """'24.0' -> '24' (the source writes these numeric columns as floats)."""
    return str(v or "").split(".")[0]


def _pos_rating(v: str) -> int | None:
    """
    '63+3' -> 63.

    EA writes positional ratings as a base plus an in-game bonus ("63+3").
    Only the base is the player's actual rating at that position; the bonus is
    a chemistry/growth effect that does not belong in a squad-quality number.
    """
    v = (v or "").strip()
    if not v:
        return None
    for sep in ("+", "-"):
        i = v.find(sep, 1)
        if i > 0:
            v = v[:i]
            break
    return int(v) if v.isdigit() else None


def _slot_grid(row: dict) -> dict[str, int | None]:
    """That player's rating in each of our seven outfield draft slots."""
    grid: dict[str, int | None] = {}
    for slot, cols in SLOT_COLUMNS.items():
        vals = [v for v in (_pos_rating(row.get(c)) for c in cols) if v is not None]
        grid[slot] = max(vals) if vals else None
    return grid


def _rows_archive():
    """Rows of the canonical archive, all editions — used to bridge FC 25's
    short names to full names/dobs (see build_fc25)."""
    with open(SOURCE, encoding="utf-8", errors="replace", newline="") as fh:
        yield from csv.DictReader(fh)


def build_ratings() -> list[dict]:
    """
    One row per (player, edition) for the five editions we map to seasons.

    No sofifa_id identity-map / age-derived-birth-year reconstruction is
    needed any more: the canonical source carries long_name, short_name and
    dob on every row, for every edition. That whole layer existed purely to
    paper over two dumps that lacked those fields (see module docstring).
    """
    out: list[dict] = []
    skipped = 0
    with open(SOURCE, encoding="utf-8", errors="replace", newline="") as fh:
        for r in csv.DictReader(fh):
            edition = _int(r.get("fifa_version"))
            if edition not in KEPT_EDITIONS:
                continue  # older editions describe seasons we do not load
            dob = (r.get("dob") or "").strip()
            ovr = _int(r.get("overall"))
            long_name = (r.get("long_name") or "").strip()
            short_name = (r.get("short_name") or "").strip()
            if not (ovr.isdigit() and len(dob) >= 4 and dob[:4].isdigit()
                    and (long_name or short_name)):
                skipped += 1
                continue
            out.append(dict(
                edition=edition,
                sofifa_id=_int(r.get("player_id")),
                variants=sorted({n for n in (long_name, short_name) if n}),
                birth_year=int(dob[:4]),
                overall=int(ovr),
                league=(r.get("league_name") or "").strip(),
                club=(r.get("club_name") or "").strip(),
                nationality=(r.get("nationality_name") or "").strip(),
                positions=[p.strip() for p in (r.get("player_positions") or "").split(",") if p.strip()],
                grid=_slot_grid(r),
            ))
    print(f"  built {len(out)} rating rows ({skipped} skipped: missing name/dob/overall)")
    out.extend(build_fc25())
    return out


def build_fc25() -> list[dict]:
    """
    EA FC 25 (-> season 2023/24) from the secondary export.

    Schema differs from the canonical archive: `Name` is a short/display name,
    there is no long_name and no dob — only `Age`, plus `Team` and `League`.
    Birth year is therefore derived (edition year minus age) and is good to
    about +/-1, which the matcher already tolerates. `Team` is carried through
    as the club so club-agreement matching can disambiguate.

    Two other files shipped alongside this one were rejected:
      - new-players-data-full.csv: has full_name AND dob, so nominally better,
        but it is a 2025-07-17 late-cycle snapshot. Players who had left top
        flight football by mid-2025 are simply absent, giving 39.8% coverage
        of the 2023/24 squads. Correlation on the players it did have was
        good (-0.890) but 60% of the season falling back to stats-only would
        recreate the cross-season inconsistency this whole mapping exists to
        avoid.
      - all_players.csv / female_players.csv: same shape as this file, wrong
        population.
    """
    if not FC25_SOURCE.exists():
        print(f"  WARNING: {FC25_SOURCE.name} missing — 2023/24 loses its preferred edition")
        return []

    # Bridge to real identities. The canonical archive carries short_name
    # ALONGSIDE long_name and dob, and almost every FC 25 player also appears
    # in an earlier edition — so an FC 25 short name can inherit that player's
    # full name. Without this, Spanish compound surnames cannot subset-match
    # at all (FBref "Vinicius Júnior" vs FC 25 "Vini Jr."), which cost ~13% of
    # La Liga 2023/24 and visibly depressed that season's lower decile.
    bridge: dict[tuple[str, int], set[str]] = {}
    for r in _rows_archive():
        short = _normalize(r.get("short_name") or "")
        long_name = (r.get("long_name") or "").strip()
        dob = (r.get("dob") or "").strip()
        if not (short and long_name and len(dob) >= 4 and dob[:4].isdigit()):
            continue
        bridge.setdefault((short, int(dob[:4])), set()).add(long_name)

    out: list[dict] = []
    bridged = 0
    with open(FC25_SOURCE, encoding="utf-8", errors="replace", newline="") as fh:
        for r in csv.DictReader(fh):
            name = (r.get("Name") or "").strip()
            ovr = _int(r.get("OVR"))
            age = _int(r.get("Age"))
            if not (name and ovr.isdigit() and age.isdigit()):
                continue
            byear = FC25_EDITION_YEAR - int(age)
            variants = {name}
            # age is derived, so allow a year either side when bridging
            for d in (0, -1, 1):
                hit = bridge.get((_normalize(name), byear + d))
                if hit and len(hit) == 1:   # unique -> safe to inherit
                    variants |= hit
                    bridged += 1
                    break
            positions = [
                p.strip()
                for p in ((r.get("Position") or "") + "," + (r.get("Alternative positions") or "")).split(",")
                if p.strip()
            ]
            out.append(dict(
                edition=FC25_EDITION,
                # no stable id in this export; synthesise one so the matcher's
                # per-player uniqueness checks still work
                sofifa_id=f"fc25-{len(out)}",
                variants=sorted(variants),
                birth_year=byear,
                overall=int(ovr),
                league=(r.get("League") or "").strip(),
                club=(r.get("Team") or "").strip(),
                nationality=(r.get("Nation") or "").strip(),
                positions=positions,
                # EA FC 25's export carries no positional grid at all. Those
                # rows resolve their grid from FC 24 instead (see
                # fifa_lookup.profile) — positional aptitude moves far more
                # slowly than rating, so borrowing one edition back is safe in
                # a way borrowing a RATING would not be.
                grid={k: None for k in SLOT_KEYS},
            ))
    print(f"  + {len(out)} EA FC 25 rows (edition 25) "
          f"({bridged} given a full name via the canonical archive)")
    return out


if __name__ == "__main__":
    print("Building unified FIFA ratings table...")
    ratings = build_ratings()

    out_path = FIFA_DIR / "fifa_ratings_normalized.csv"
    with open(out_path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["edition", "sofifa_id", "display_name", "norm_variants",
                    "birth_year", "overall", "league", "club",
                    "nationality", "positions"] + [f"pos_{k}" for k in SLOT_KEYS])
        for r in ratings:
            norms = sorted({_normalize(v) for v in r["variants"] if _normalize(v)})
            w.writerow(
                [r["edition"], r["sofifa_id"], r["variants"][0] if r["variants"] else "",
                 ";".join(norms), r["birth_year"], r["overall"], r["league"], r["club"],
                 r.get("nationality", ""), ";".join(r.get("positions", []))]
                + [(r.get("grid") or {}).get(k) or "" for k in SLOT_KEYS]
            )
    print(f"  wrote {out_path}")
    per_edition = Counter(r["edition"] for r in ratings)
    for e in sorted(per_edition, key=int):
        print(f"    edition {e}: {per_edition[e]} players")
