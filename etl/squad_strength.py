"""
Computes a strength value per club_season and reports the spread, so the
draft-spin weighting (see the dream-xi-draft-mechanic memory) can be built
on measured numbers rather than a guess.

Read-only. Strength is the mean overall_rating of the club's most-used
players — NOT the whole squad, because a 40-man squad list is dominated by
fringe players who were never really part of that team and would flatten
every club toward the same mid value.
"""
from __future__ import annotations

import os
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import requests
from dotenv import load_dotenv

sys.stdout.reconfigure(encoding="utf-8")
load_dotenv(Path(__file__).parent / ".env")
SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
H = {"apikey": SERVICE_KEY, "Authorization": f"Bearer {SERVICE_KEY}"}

# how many top-rated players define a squad's strength (a matchday XI + a
# little rotation cover)
CORE_SQUAD = 14
MIN_MINUTES = 450  # exclude cameo appearances from "was part of this squad"


def get_all(table: str, params: dict) -> list[dict]:
    out, off = [], 0
    while True:
        r = requests.get(f"{SUPABASE_URL}/rest/v1/{table}", headers=H,
                         params=dict(params, limit=1000, offset=off))
        r.raise_for_status()
        b = r.json()
        out += b
        if len(b) < 1000:
            break
        off += 1000
    return out


def load_squads() -> dict[int, dict]:
    """club_season_id -> {label, league, club, strength, n_core}"""
    leagues = {l["id"]: l["name"] for l in get_all("leagues", {"select": "id,name"})}
    seasons = {s["id"]: s for s in get_all("seasons", {"select": "id,league_id,label"})}
    club_seasons = {c["id"]: c for c in get_all(
        "club_seasons", {"select": "id,season_id,club_name,final_position,points"})}
    stats = get_all("player_season_stats",
                    {"select": "club_season_id,overall_rating,minutes"})

    by_cs: dict[int, list[int]] = defaultdict(list)
    for s in stats:
        if s["minutes"] and s["minutes"] >= MIN_MINUTES and s["overall_rating"] is not None:
            by_cs[s["club_season_id"]].append(s["overall_rating"])

    out = {}
    for cs_id, ratings in by_cs.items():
        cs = club_seasons.get(cs_id)
        if not cs:
            continue
        sea = seasons[cs["season_id"]]
        core = sorted(ratings, reverse=True)[:CORE_SQUAD]
        out[cs_id] = dict(
            label=sea["label"],
            league=leagues[sea["league_id"]],
            club=cs["club_name"],
            final_position=cs["final_position"],
            points=cs["points"],
            strength=round(statistics.mean(core), 2),
            n_core=len(core),
        )
    return out


if __name__ == "__main__":
    squads = load_squads()
    vals = sorted(s["strength"] for s in squads.values())
    n = len(vals)
    print(f"club_seasons with a usable squad: {n}")
    print(f"  min={vals[0]}  p10={vals[int(n*.10)]}  median={vals[n//2]}  "
          f"p90={vals[int(n*.90)]}  max={vals[-1]}")
    print(f"  mean={statistics.mean(vals):.2f}  stdev={statistics.pstdev(vals):.2f}")

    ranked = sorted(squads.values(), key=lambda s: -s["strength"])
    print("\nSTRONGEST 12:")
    for s in ranked[:12]:
        print(f"   {s['strength']:5.1f}  {s['club']:20s} {s['league']:15s} {s['label']}"
              f"   (finished {s['final_position']})")
    print("\nWEAKEST 12:")
    for s in ranked[-12:]:
        print(f"   {s['strength']:5.1f}  {s['club']:20s} {s['league']:15s} {s['label']}"
              f"   (finished {s['final_position']})")

    # does squad strength actually track real league finish? sanity check
    pairs = [(s["strength"], s["final_position"]) for s in squads.values()
             if s["final_position"]]
    if pairs:
        xs = [p[0] for p in pairs]
        ys = [float(p[1]) for p in pairs]
        mx, my = statistics.mean(xs), statistics.mean(ys)
        cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / len(xs)
        r = cov / (statistics.pstdev(xs) * statistics.pstdev(ys))
        print(f"\ncorrelation(strength, final_position) = {r:+.3f}"
              "   (negative is expected: stronger squad -> better/lower position)")
