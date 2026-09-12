"""
How often does a squad player START, given how much of a season he plays?

A substitute scoring in the 3rd minute is impossible, but our engine drew every
goal minute uniformly across the 90 regardless of who scored it. Before that can
be fixed, the engine needs to know whether the scorer was on the pitch from
kick-off — which is what this measures.
"""
from __future__ import annotations
import os, statistics, sys
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

rows = fetch("player_season_stats", {"select": "club_season_id,minutes,appearances"})
by = defaultdict(list)
for r in rows:
    if (r["minutes"] or 0) > 0 and (r["appearances"] or 0) > 0:
        by[r["club_season_id"]].append(r)

# A start puts a player on for about 85 minutes, a substitute appearance for
# about 22. Minutes per appearance therefore implies how often he started.
START_MINUTES, SUB_MINUTES = 85.0, 22.0
xs, ys = [], []
for ps in by.values():
    ps.sort(key=lambda r: -r["minutes"])
    for r in ps[:20]:
        share = r["minutes"] / 3420
        mpa = r["minutes"] / r["appearances"]
        start = max(0.0, min(1.0, (mpa - SUB_MINUTES) / (START_MINUTES - SUB_MINUTES)))
        xs.append(share); ys.append(start)

mx, my = statistics.mean(xs), statistics.mean(ys)
num = sum((a - mx) * (b - my) for a, b in zip(xs, ys))
den = sum((a - mx) ** 2 for a in xs)
slope = num / den
intercept = my - slope * mx
ss_res = sum((b - (intercept + slope * a)) ** 2 for a, b in zip(xs, ys))
ss_tot = sum((b - my) ** 2 for b in ys)

print(f"start rate = {intercept:.4f} + {slope:.4f} * minutes share")
print(f"   n = {len(xs)}   R^2 = {1 - ss_res / ss_tot:.3f}\n")
print(f"{'share':>7} {'fitted':>8} {'measured':>10}")
bands = defaultdict(list)
for a, b in zip(xs, ys):
    bands[round(a * 10) / 10].append(b)
for share in sorted(bands):
    if len(bands[share]) < 30: continue
    fitted = max(0.0, min(1.0, intercept + slope * share))
    print(f"{share:>7.1f} {fitted:>8.2f} {statistics.mean(bands[share]):>10.2f}")
