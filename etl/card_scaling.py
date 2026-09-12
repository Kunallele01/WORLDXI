"""
How do bookings scale with minutes played?

Projecting a part-season booking rate over a full season overstates it badly:
Luka Modric's real 7 yellows in 1,744 minutes became 15 in a simulated season.
Cards are not linear in minutes — a booked player is often substituted, and one
already on a yellow is managed carefully — so this fits the real exponent.
"""
from __future__ import annotations
import math, os, statistics, sys
from pathlib import Path
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


rows = [r for r in fetch("player_season_stats",
        {"select": "minutes,yellow_cards,red_cards,goals"}) if (r["minutes"] or 0) >= 450]


def fit_exponent(col: str) -> float:
    """Least squares on log(count) = log(a) + k*log(minutes), over scorers only."""
    xs, ys = [], []
    for r in rows:
        v = r[col] or 0
        if v <= 0: continue
        xs.append(math.log(r["minutes"]))
        ys.append(math.log(v))
    mx, my = statistics.mean(xs), statistics.mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    den = sum((x - mx) ** 2 for x in xs)
    return num / den


for col in ("yellow_cards", "red_cards", "goals"):
    k = fit_exponent(col)
    print(f"{col:>13}: count scales with minutes^{k:.3f}"
          f"   ({'sub-linear' if k < 0.9 else 'roughly linear'})")

k = fit_exponent("yellow_cards")
print(f"\nUsing minutes^{k:.3f} for yellows:")
for mins, y, who in [(1744, 7, "Modric, his most-booked season"),
                     (3092, 15, "Pique 2019/20"),
                     (2559, 14, "Oscar Gil"),
                     (2733, 4, "Modric, a quiet season")]:
    linear = y * 3420 / mins
    fitted = y * (3420 / mins) ** k
    print(f"  {who:<32} {y:>2}Y in {mins}  ->  linear {linear:>5.1f}   fitted {fitted:>5.1f}")

full = [r for r in rows if r["minutes"] >= 2900]
print(f"\nreality check: players with 2900+ minutes average "
      f"{statistics.mean(r['yellow_cards'] or 0 for r in full):.2f} yellows, "
      f"max {max(r['yellow_cards'] or 0 for r in full)}")
