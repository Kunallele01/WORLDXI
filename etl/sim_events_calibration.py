"""
Measures everything needed to turn a scoreline into a match report:
who scored, who assisted, who was booked.

Same rule as the rest of the engine — nothing is invented. Each constant here
is a median or a ratio taken from the 200 loaded club-seasons, and the ones
that turn out NOT to be usable are called out rather than quietly fitted.
"""
from __future__ import annotations
import os, sys, statistics
from pathlib import Path
from collections import defaultdict
import requests
from dotenv import load_dotenv

sys.stdout.reconfigure(encoding="utf-8")
load_dotenv(Path(__file__).parent / ".env")
U = os.environ["SUPABASE_URL"]; K = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
H = {"apikey": K, "Authorization": f"Bearer {K}"}

def fetch(t, p):
    out, off = [], 0
    with requests.Session() as s:
        s.headers.update(H)
        while True:
            r = s.get(f"{U}/rest/v1/{t}", params=dict(p, limit=1000, offset=off), timeout=180)
            r.raise_for_status(); b = r.json()
            if not b: return out
            out += b; off += 1000

clubs = {c["id"]: c for c in fetch("club_seasons",
    {"select": "id,club_name,season_id,goals_for,goals_against"})}
rows = fetch("player_season_stats", {"select":
    "club_season_id,minutes,goals,assists,yellow_cards,red_cards,primary_position,overall_rating"})

reg = [r for r in rows if (r["minutes"] or 0) >= 450]

# ---- per-90 medians by position -----------------------------------------
by_pos = defaultdict(lambda: defaultdict(list))
for r in reg:
    pos = r["primary_position"]
    if not pos: continue
    m = r["minutes"]
    for col in ("goals", "assists", "yellow_cards", "red_cards"):
        by_pos[pos][col].append((r[col] or 0) * 90.0 / m)

ORDER = ["GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST"]
print(f"{'role':>8} {'n':>5} {'goals/90':>10} {'assists/90':>11} {'yellow/90':>10} {'red/90':>9}")
for pos in ORDER:
    d = by_pos.get(pos)
    if not d: continue
    print(f"{pos:>8} {len(d['goals']):>5} "
          f"{statistics.median(d['goals']):>10.4f} "
          f"{statistics.median(d['assists']):>11.4f} "
          f"{statistics.median(d['yellow_cards']):>10.4f} "
          f"{statistics.median(d['red_cards']):>9.5f}")

# ---- how often is a goal assisted? --------------------------------------
tot_goals = sum(r["goals"] or 0 for r in rows)
tot_assists = sum(r["assists"] or 0 for r in rows)
print(f"\nplayer goals {tot_goals}, player assists {tot_assists}"
      f"  ->  {tot_assists / tot_goals * 100:.1f}% of goals carry a recorded assist")

# ---- team-level card rates, to sanity-check the per-90 numbers -----------
per_club = defaultdict(lambda: [0, 0])
for r in rows:
    c = per_club[r["club_season_id"]]
    c[0] += r["yellow_cards"] or 0
    c[1] += r["red_cards"] or 0
ys = [v[0] / 38 for v in per_club.values()]
rs = [v[1] / 38 for v in per_club.values()]
print(f"team yellows per match: median {statistics.median(ys):.3f}  mean {statistics.mean(ys):.3f}")
print(f"team reds    per match: median {statistics.median(rs):.4f} mean {statistics.mean(rs):.4f}")

# ---- do club goals equal the sum of their players' goals? ---------------
# If not, attributing every simulated goal to a player would overstate the
# squad, because own goals and non-regulars are missing from the XI.
ratios = []
for cid, c in clubs.items():
    if not c.get("goals_for"): continue
    pg = sum(r["goals"] or 0 for r in rows if r["club_season_id"] == cid)
    if pg: ratios.append(pg / c["goals_for"])
print(f"\nsum(player goals) / club goals_for: median {statistics.median(ratios):.3f} "
      f"(n={len(ratios)})")

# ---- how concentrated is scoring inside an XI? --------------------------
shares = []
for cid in clubs:
    xi = sorted([r for r in rows if r["club_season_id"] == cid and (r["minutes"] or 0) >= 450],
                key=lambda r: -r["minutes"])[:11]
    if len(xi) < 11: continue
    tot = sum(r["goals"] or 0 for r in xi)
    if tot < 10: continue
    top = max((r["goals"] or 0) for r in xi)
    shares.append(top / tot)
print(f"top scorer's share of his XI's goals: median {statistics.median(shares):.3f} "
      f"(n={len(shares)})")
