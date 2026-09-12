"""
Fits the exact coefficients the match engine uses, and reports how wrong each
one is. Nothing here is chosen; everything is solved for and then scored
against the 200 real club-seasons.

Produces the numbers that get pasted into SimModel.kt, and the honest error
bars that go beside them.
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
HEADERS = {"apikey": SERVICE_KEY, "Authorization": f"Bearer {SERVICE_KEY}"}
MATCHES = 38


def fetch(table, params):
    out, offset = [], 0
    with requests.Session() as s:
        s.headers.update(HEADERS)
        while True:
            r = s.get(f"{SUPABASE_URL}/rest/v1/{table}",
                      params=dict(params, limit=1000, offset=offset), timeout=180)
            r.raise_for_status()
            b = r.json()
            if not b:
                return out
            out += b
            offset += 1000


def fit(xs, ys):
    n = len(xs); mx, my = sum(xs)/n, sum(ys)/n
    num = sum((a-mx)*(b-my) for a, b in zip(xs, ys))
    den = sum((a-mx)**2 for a in xs)
    s = num/den if den else 0.0
    return s, my - s*mx


def corr(xs, ys):
    n = len(xs); mx, my = sum(xs)/n, sum(ys)/n
    num = sum((a-mx)*(b-my) for a, b in zip(xs, ys))
    den = (sum((a-mx)**2 for a in xs)*sum((b-my)**2 for b in ys))**0.5
    return num/den if den else 0.0


def rmse(p, a):
    return (sum((x-y)**2 for x, y in zip(p, a))/len(p))**0.5


clubs = {c["id"]: c for c in fetch("club_seasons", {
    "select": "id,club_name,season_id,goals_for,goals_against,points"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select":
    "club_season_id,primary_position,minutes,overall_rating,npxg,xa,save_pct"})
squads = defaultdict(list)
for s in stats:
    if (s["minutes"] or 0) >= 450:
        squads[s["club_season_id"]].append(s)

DEF_WEIGHT = {"GK": 1.00, "CB": 1.00, "FB": 0.85, "DM": 0.70,
              "CM": 0.45, "CAM": 0.20, "Winger": 0.20, "ST": 0.10}

rows = []
for cs_id, players in squads.items():
    c = clubs.get(cs_id)
    if not c or c.get("goals_for") is None:
        continue
    xi = sorted(players, key=lambda p: -p["minutes"])[:11]
    if len(xi) < 11:
        continue
    npxg = [p["npxg"] for p in xi]
    if any(v is None for v in npxg):
        continue
    gks = [p for p in xi if p["primary_position"] == "GK"]
    gk = max(gks, key=lambda p: p["minutes"]) if gks else None

    # Defensive rating: every outfielder counts, weighted by how much
    # defending his position is actually asked to do. A weighted mean, not a
    # back-four average, so a midfield of forwards is correctly punished.
    num = sum(DEF_WEIGHT.get(p["primary_position"], 0.4) * p["overall_rating"] for p in xi)
    den = sum(DEF_WEIGHT.get(p["primary_position"], 0.4) for p in xi)
    rows.append({
        "club": c["club_name"], "season": seasons[c["season_id"]],
        "att": sum(float(v) * 90.0 / p["minutes"] for v, p in zip(npxg, xi)),
        "def_rating": num / den,
        "gk_save": float(gk["save_pct"]) if gk and gk["save_pct"] is not None else None,
        "gf": c["goals_for"] / MATCHES,
        "ga": c["goals_against"] / MATCHES,
        "points": c["points"],
    })

print(f"club-seasons usable: {len(rows)}\n")

print("=" * 78)
print("ATTACK COEFFICIENT")
print("=" * 78)
x = [r["att"] for r in rows]; y = [r["gf"] for r in rows]
sa, ia = fit(x, y)
pa = [sa*v + ia for v in x]
print(f"  goals_for/match = {sa:.4f} * sum_npxg90 + {ia:+.4f}")
print(f"  r = {corr(x,y):+.3f}   RMSE {rmse(pa,y):.3f}/match  ({rmse(pa,y)*MATCHES:.1f}/season)")

print("\n" + "=" * 78)
print("DEFENCE COEFFICIENT")
print("=" * 78)
xd = [r["def_rating"] for r in rows]; yd = [r["ga"] for r in rows]
sd, idc = fit(xd, yd)
pd_ = [sd*v + idc for v in xd]
print(f"  goals_against/match = {sd:.4f} * def_rating + {idc:+.4f}")
print(f"  r = {corr(xd,yd):+.3f}   RMSE {rmse(pd_,yd):.3f}/match  ({rmse(pd_,yd)*MATCHES:.1f}/season)")
print(f"  weighting used: {DEF_WEIGHT}")

sv = [r for r in rows if r["gk_save"] is not None]
if sv:
    resid = [r["ga"] - (sd*r["def_rating"] + idc) for r in sv]
    saves = [r["gk_save"] for r in sv]
    ss, si = fit(saves, resid)
    print(f"\n  keeper save%% on top: residual_ga = {ss:.5f} * save_pct {si:+.4f}")
    print(f"  r = {corr(saves,resid):+.3f}  — a good keeper saves this much beyond his rating")

print("\n" + "=" * 78)
print("DOES IT PREDICT THE LEAGUE TABLE?")
print("=" * 78)
print("""
The real test. Goals are only interesting if they produce the right season. A
Poisson model on the fitted expected goals gives an expected points total; if
that lands near the actual points, the engine describes football rather than
merely fitting two scatter plots.
""")
import math


def poisson_points(gf, ga):
    """Expected points per match for a team scoring gf and conceding ga, both
    Poisson. Summed over plausible scorelines."""
    def pmf(k, lam):
        return math.exp(-lam) * lam**k / math.factorial(k)
    w = d = 0.0
    for i in range(9):
        for j in range(9):
            p = pmf(i, gf) * pmf(j, ga)
            if i > j: w += p
            elif i == j: d += p
    return 3*w + d


pred_points, actual_points = [], []
for r in rows:
    gf = sa*r["att"] + ia
    ga = sd*r["def_rating"] + idc
    if r["gk_save"] is not None:
        ga += ss*r["gk_save"] + si
    gf, ga = max(0.2, gf), max(0.2, ga)
    pred_points.append(poisson_points(gf, ga) * MATCHES)
    actual_points.append(r["points"])
print(f"  predicted vs actual points:  r = {corr(pred_points, actual_points):+.3f}")
print(f"  RMSE {rmse(pred_points, actual_points):.1f} points over a 38-game season")
print(f"  mean predicted {statistics.mean(pred_points):.1f} vs actual {statistics.mean(actual_points):.1f}")

print("\n  biggest misses:")
worst = sorted(zip(rows, pred_points, actual_points), key=lambda t: -abs(t[1]-t[2]))[:6]
for r, p, a in worst:
    print(f"     {r['club'][:20]:20} {r['season']}  predicted {p:5.1f} actual {a:3.0f} pts")

print("\n  best predictions:")
best = sorted(zip(rows, pred_points, actual_points), key=lambda t: abs(t[1]-t[2]))[:5]
for r, p, a in best:
    print(f"     {r['club'][:20]:20} {r['season']}  predicted {p:5.1f} actual {a:3.0f} pts")
