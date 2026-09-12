"""
Does the defence model concede what real defences concede?

Reported: an XI whose defenders and keeper were all 80+ shipped 46 goals, while
the real Getafe side it was compared against conceded 40. Checking whether that
is the model being too leaky or the comparison being unfair.
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

WEIGHT = {"GK":1.00,"CB":1.00,"FB":0.85,"DM":0.70,"CM":0.45,"CAM":0.20,"Winger":0.20,"ST":0.10}
SLOPE, INTERCEPT = -0.0851, 8.0418

clubs = {c["id"]: c for c in fetch("club_seasons",
    {"select": "id,club_name,season_id,goals_against,final_position"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
rows = fetch("player_season_stats",
    {"select": "club_season_id,minutes,overall_rating,primary_position"})

by_club = defaultdict(list)
for r in rows:
    if (r["minutes"] or 0) >= 450 and r["overall_rating"] and r["primary_position"]:
        by_club[r["club_season_id"]].append(r)

points = []
for cid, ps in by_club.items():
    c = clubs.get(cid)
    if not c or c.get("goals_against") is None: continue
    xi = sorted(ps, key=lambda r: -r["minutes"])[:11]
    if len(xi) < 11: continue
    num = sum(WEIGHT.get(p["primary_position"], 0.4) * p["overall_rating"] for p in xi)
    den = sum(WEIGHT.get(p["primary_position"], 0.4) for p in xi)
    rating = num / den
    points.append((rating, c["goals_against"], c["club_name"], seasons[c["season_id"]]))

print(f"{'def rating':>11} {'n':>4} {'real GA: min':>13} {'median':>8} {'max':>6} {'model':>7}")
buckets = defaultdict(list)
for rating, ga, *_ in points:
    buckets[int(rating)][:0] = [ga]
for band in sorted(buckets):
    v = sorted(buckets[band])
    if len(v) < 3: continue
    model = (SLOPE * band + INTERCEPT) * 38
    print(f"{band:>11} {len(v):>4} {v[0]:>13} {statistics.median(v):>8.0f} {v[-1]:>6} {model:>7.0f}")

print("\nsides rated 79-81, what they really conceded:")
band = sorted([p for p in points if 79 <= p[0] < 81.5], key=lambda p: p[1])
for rating, ga, name, season in band[:5] + band[-5:]:
    print(f"   {name:<22} {season}  rating {rating:.1f}  conceded {ga}")
gas = [p[1] for p in band]
print(f"   -> n={len(gas)} min {min(gas)} median {statistics.median(gas):.0f} max {max(gas)}")
model = (SLOPE * 80 + INTERCEPT) * 38
print(f"   -> model says a rating-80 side concedes {model:.0f}")

# Where did the outlier defences sit?
print("\nthe meanest real defences in the data:")
for rating, ga, name, season in sorted(points, key=lambda p: p[1])[:6]:
    print(f"   {name:<22} {season}  rating {rating:.1f}  conceded {ga}")
