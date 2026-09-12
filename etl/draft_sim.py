"""
Simulates the draft spin to measure how unfair an unweighted random
team+season draw actually is, and to test weighting schemes against it.

Read-only; no DB writes. Run directly to compare strategies.

Draft model (per the dream-xi-draft-mechanic memory): 11 rounds, one per
formation slot. Each round spins a random (club, season) and the user picks
from THAT squad. A real person can only be drafted once across the whole run
(dedupe on player_id, not player_season_stat_id).
"""
from __future__ import annotations

import os
import random
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

# 4-3-3, the shape the app defaults to
FORMATION = ["GK", "CB", "CB", "FB", "FB", "DM", "CM", "CAM", "Winger", "Winger", "ST"]
MIN_MINUTES = 450


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


def load():
    """-> (squad_pool, strength) where squad_pool[cs_id][role] = [(rating, player_id)]"""
    from squad_strength import load_squads
    squads = load_squads()
    stats = get_all("player_season_stats",
                    {"select": "club_season_id,player_id,primary_position,overall_rating,minutes"})
    pool: dict[int, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for s in stats:
        if not s["minutes"] or s["minutes"] < MIN_MINUTES or s["overall_rating"] is None:
            continue
        pool[s["club_season_id"]][s["primary_position"]].append(
            (s["overall_rating"], s["player_id"]))
    for cs in pool.values():
        for role in cs:
            cs[role].sort(reverse=True)
    return pool, squads


def run_draft(pool, cs_ids, weights, rng) -> list[int]:
    """
    Plays one full draft; returns the 11 chosen ratings.

    Models the ACTUAL mechanic: each round spins one (club, season) and the
    user picks any player from that squad into any still-empty slot. That
    gives the user real agency early — take the best player available and
    slot him — and squeezes them late, when only awkward slots remain and
    the spun squad may have nobody for them. An earlier version of this
    simulation fixed the role per round and re-spun until that role appeared,
    which quietly removed both the agency and the late-round squeeze, and so
    understated how streaky the draft really is.
    """
    taken: set[int] = set()
    remaining: dict[str, int] = defaultdict(int)
    for role in FORMATION:
        remaining[role] += 1
    picked: list[int] = []

    while sum(remaining.values()) > 0:
        cs = rng.choices(cs_ids, weights=weights, k=1)[0]
        best = None
        best_role = None
        for role, need in remaining.items():
            if need <= 0:
                continue
            for rating, pid in pool[cs].get(role, []):
                if pid in taken:
                    continue
                if best is None or rating > best[0]:
                    best, best_role = (rating, pid), role
                break  # list is sorted; first untaken is that role's best
        if best is None:
            continue  # squad offers nothing usable — spin again
        taken.add(best[1])
        remaining[best_role] -= 1
        picked.append(best[0])
    return picked


def summarise(name, runs):
    xis = [statistics.mean(r) for r in runs]
    worst = [min(r) for r in runs]
    xis.sort()
    worst.sort()
    n = len(xis)
    spread = [max(r) - min(r) for r in runs]
    print(f"\n{name}")
    print(f"   XI mean rating : p5={xis[int(n*.05)]:.1f}  median={xis[n//2]:.1f}  "
          f"p95={xis[int(n*.95)]:.1f}   (spread {xis[int(n*.95)] - xis[int(n*.05)]:.1f})")
    print(f"   weakest pick   : p5={worst[int(n*.05)]:.0f}  median={worst[n//2]:.0f}  "
          f"p95={worst[int(n*.95)]:.0f}")
    print(f"   within-XI range: median {statistics.median(spread):.0f} rating points "
          f"between a run's best and worst player")


if __name__ == "__main__":
    rng = random.Random(42)
    pool, squads = load()
    cs_ids = [c for c in squads if c in pool]
    strengths = [squads[c]["strength"] for c in cs_ids]
    mean_s = statistics.mean(strengths)
    sd_s = statistics.pstdev(strengths)
    print(f"pool: {len(cs_ids)} club_seasons, strength mean={mean_s:.2f} sd={sd_s:.2f}")

    N = 4000
    uniform = [1.0] * len(cs_ids)
    runs_u = [run_draft(pool, cs_ids, uniform, rng) for _ in range(N)]
    summarise("UNWEIGHTED (current behaviour)", runs_u)

    # Gaussian taper: squads far from the mean in EITHER direction come up
    # less often, without excluding any team-season outright.
    for tau in (1.5, 1.0, 0.75):
        w = [2.718281828 ** (-((s - mean_s) / (tau * sd_s)) ** 2) for s in strengths]
        runs_w = [run_draft(pool, cs_ids, w, rng) for _ in range(N)]
        summarise(f"WEIGHTED  tau={tau}  (taper toward mid-strength squads)", runs_w)
