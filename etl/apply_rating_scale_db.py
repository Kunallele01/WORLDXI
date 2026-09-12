"""
Writes the era-rescaled overall_rating into Supabase for every season whose FIFA
anchor predates EA's FIFA 15-17 rating inflation. See rating_scale.py for why.

DRY RUN BY DEFAULT; pass --apply to write. Then run
`python backfill_squad_strength.py --apply` — squad strength is derived from
these ratings and the draft tiers read it.

Only overall_rating changes. Player identities, fixtures, stats and positional
grids are untouched, which is why this is an update rather than a delete and
reload: the result is the same, with none of the churn.

Two populations, handled differently because they were staged differently:

  2009/10-2013/14  re-staged with the rescale built in (stage_seasons_2010_2014),
                   so the database takes the staged rating.

  2014/15-2015/16  staged before the rescale existed and edited in the database
                   since (the interceptions and positional backfills), so the
                   current value is KEPT and only the scale correction is added.
                   Each row's FIFA anchor is re-found with the same lookup that
                   staged it; if that anchor is edition 16 or earlier, the
                   difference to_modern_scale(base) - base is applied. 2015/16
                   is here only for the players whose anchor fell back to FIFA
                   16; everyone matched on FIFA 17 is already on the modern scale.
"""
from __future__ import annotations

import csv
import os
import statistics
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from dotenv import load_dotenv

from fifa_lookup import FifaLookup
from load_staged_2015_2019 import club as renamed_club
from rating_scale import to_modern_scale

APPLY = "--apply" in sys.argv
HERE = Path(__file__).parent
STAGED = HERE / "staged"
load_dotenv(HERE / ".env")
URL = os.environ["SUPABASE_URL"]
KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {"apikey": KEY, "Authorization": f"Bearer {KEY}", "Content-Type": "application/json"}

RESTAGED = [f"{y}/{str(y + 1)[2:]}" for y in range(2009, 2014)]
CORRECTED = ["2014/15", "2015/16"]
LEAGUE_KEY = {"Premier League": "PL", "La Liga": "LaLiga"}
WORKERS = 6


def get_all(table: str, params: dict) -> list[dict]:
    out, offset = [], 0
    with requests.Session() as s:
        s.headers.update(HEADERS)
        while True:
            r = s.get(f"{URL}/rest/v1/{table}", params=dict(params, limit=1000, offset=offset), timeout=180)
            r.raise_for_status()
            batch = r.json()
            if not batch:
                return out
            out += batch
            offset += 1000


def db_rows(season_id: int) -> list[dict]:
    clubs = get_all("club_seasons", {"select": "id,club_name", "season_id": f"eq.{season_id}"})
    names = {c["id"]: c["club_name"] for c in clubs}
    rows = get_all("player_season_stats", {
        "select": "id,overall_rating,club_season_id,players(full_name,date_of_birth)",
        "club_season_id": f"in.({','.join(map(str, names))})",
    })
    for r in rows:
        r["club_name"] = names[r["club_season_id"]]
    return rows


def patch(update: tuple[int, int, int]) -> None:
    row_id, _old, new = update
    for attempt in range(6):
        try:
            r = requests.patch(f"{URL}/rest/v1/player_season_stats", headers=HEADERS,
                               params={"id": f"eq.{row_id}"}, json={"overall_rating": new}, timeout=60)
            r.raise_for_status()
            return
        except requests.RequestException:
            # Same lesson as backfill_positions: thousands of single-row writes
            # WILL hit a timeout somewhere. Back off and retry; the write is a
            # plain overwrite, so repeating it is safe.
            time.sleep(2 ** attempt)
    raise RuntimeError(f"gave up on player_season_stats {row_id}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    leagues = {l["id"]: l["name"] for l in get_all("leagues", {"select": "id,name"})}
    seasons = get_all("seasons", {"select": "id,label,league_id"})
    lookup = FifaLookup()
    updates: list[tuple[int, int, int]] = []

    for s in sorted(seasons, key=lambda s: (s["label"], s["league_id"])):
        label, league = s["label"], leagues[s["league_id"]]
        if label not in RESTAGED + CORRECTED:
            continue
        rows = db_rows(s["id"])
        changed, shifts, unmatched = [], [], 0

        if label in RESTAGED:
            tag = f"{LEAGUE_KEY[league]}_{label.replace('/', '-')}"
            staged = {}
            with open(STAGED / f"playerseasons_{tag}.tsv", encoding="utf-8", newline="") as fh:
                for r in csv.DictReader(fh, delimiter="\t"):
                    if r["overall_rating"] != "":
                        staged[(r["full_name"], int(r["birth_year"]), renamed_club(r["club_name"]))] = int(r["overall_rating"])
            for r in rows:
                p = r["players"]
                key = (p["full_name"], int(p["date_of_birth"][:4]), r["club_name"])
                if key not in staged:
                    unmatched += 1
                    continue
                if staged[key] != r["overall_rating"]:
                    changed.append((r["id"], r["overall_rating"], staged[key]))
                    shifts.append(staged[key] - (r["overall_rating"] or 0))
        else:
            for r in rows:
                p = r["players"]
                if r["overall_rating"] is None or not p["date_of_birth"]:
                    continue
                profile, _ = lookup.get_profile(p["full_name"], int(p["date_of_birth"][:4]), label, r["club_name"])
                if profile is None or int(profile.edition) > 16:
                    continue
                shift = to_modern_scale(profile.edition, profile.overall) - profile.overall
                new = min(99, int(round(r["overall_rating"] + shift)))
                if new != r["overall_rating"]:
                    changed.append((r["id"], r["overall_rating"], new))
                    shifts.append(new - r["overall_rating"])

        updates += changed
        mean = f"{statistics.mean(shifts):+.2f}" if shifts else "  n/a"
        print(f"{league:15} {label}: {len(rows):4} rows, {len(changed):4} change, mean shift {mean}"
              + (f", {unmatched} staged-to-DB misses" if unmatched else ""))
        for row_id, old, new in sorted(changed, key=lambda u: -u[2])[:2]:
            print(f"      e.g. id {row_id}: {old} -> {new}")

    print(f"\n{len(updates)} ratings to update")
    if not APPLY:
        print("DRY RUN — pass --apply to write, then run backfill_squad_strength.py --apply")
        return
    with ThreadPoolExecutor(WORKERS) as pool:
        list(pool.map(patch, updates))
    print(f"wrote {len(updates)} ratings. NOW RUN: python backfill_squad_strength.py --apply")


if __name__ == "__main__":
    main()
