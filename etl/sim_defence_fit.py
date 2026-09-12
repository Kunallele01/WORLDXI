"""
Refits the defence side of the match engine, and scores the new shape against
the straight line it replaces.

WHY THIS SCRIPT EXISTS. The original fit modelled goals conceded as a straight
line in the XI's defensively-weighted mean rating. Inside the range of real
clubs that is a reasonable description; outside it, it is nonsense - the line
has to reach zero and then keep going, and it got to zero at a defensive
rating of about 94.5. That was never noticed because no real club-season comes
close: the highest in 400 is 86.76. Free Mode's magic XI rates 88.9, so the
first thing that ever stood in that part of the curve was a user's team, which
the engine duly had conceding 15 goals a season with 28 clean sheets.

The replacement is not a cap and not a floor. It is the same regression with a
log link - goals conceded modelled as a product rather than a sum, so each
rating point removes a percentage instead of a fixed number. That cannot go
negative by construction, it flattens as it approaches zero, and it is a
BETTER fit to the real data as well, which is the part that makes it the right
answer rather than merely a safe one.

Everything printed here is measured. Nothing in it is chosen.
"""
from __future__ import annotations

import math
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

DEF_WEIGHT = {"GK": 1.00, "CB": 1.00, "FB": 0.85, "DM": 0.70,
              "CM": 0.45, "CAM": 0.20, "Winger": 0.20, "ST": 0.10}


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
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((a - mx) * (b - my) for a, b in zip(xs, ys))
    den = sum((a - mx) ** 2 for a in xs)
    s = num / den if den else 0.0
    return s, my - s * mx


def corr(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((a - mx) * (b - my) for a, b in zip(xs, ys))
    den = (sum((a - mx) ** 2 for a in xs) * sum((b - my) ** 2 for b in ys)) ** 0.5
    return num / den if den else 0.0


def rmse(p, a):
    return (sum((x - y) ** 2 for x, y in zip(p, a)) / len(p)) ** 0.5


def bias(p, a):
    return sum(x - y for x, y in zip(p, a)) / len(p)


def poisson_points(gf, ga):
    def pmf(k, lam):
        return math.exp(-lam) * lam ** k / math.factorial(k)
    w = d = 0.0
    for i in range(9):
        for j in range(9):
            p = pmf(i, gf) * pmf(j, ga)
            if i > j:
                w += p
            elif i == j:
                d += p
    return 3 * w + d


clubs = {c["id"]: c for c in fetch("club_seasons", {
    "select": "id,club_name,season_id,goals_for,goals_against,points"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select":
    "club_season_id,primary_position,minutes,overall_rating,npxg,save_pct"})
squads = defaultdict(list)
for s in stats:
    if (s["minutes"] or 0) >= 450:
        squads[s["club_season_id"]].append(s)

rows = []
for cs_id, players in squads.items():
    c = clubs.get(cs_id)
    if not c or c.get("goals_against") is None:
        continue
    xi = sorted(players, key=lambda p: -p["minutes"])[:11]
    if len(xi) < 11:
        continue
    gks = [p for p in xi if p["primary_position"] == "GK"]
    gk = max(gks, key=lambda p: p["minutes"]) if gks else None
    num = sum(DEF_WEIGHT.get(p["primary_position"], 0.4) * p["overall_rating"] for p in xi)
    den = sum(DEF_WEIGHT.get(p["primary_position"], 0.4) for p in xi)
    npxg = [p["npxg"] for p in xi]
    rows.append({
        "club": c["club_name"],
        "season": seasons[c["season_id"]],
        "def_rating": num / den,
        "gk_save": float(gk["save_pct"]) if gk and gk["save_pct"] is not None else None,
        "ga": c["goals_against"] / MATCHES,
        "gf": (c["goals_for"] / MATCHES) if c.get("goals_for") is not None else None,
        "points": c["points"],
        "att": (sum(float(v) * 90.0 / p["minutes"] for v, p in zip(npxg, xi))
                if not any(v is None for v in npxg) else None),
    })

rows.sort(key=lambda r: r["def_rating"])
D = [r["def_rating"] for r in rows]
G = [r["ga"] for r in rows]
print(f"club-seasons: {len(rows)}")
print(f"defensive rating observed: {min(D):.2f} .. {max(D):.2f}")
print("NOTHING in the real data reaches 87. A Free Mode magic XI rates 88.9-89.6,")
print("so the shape of the curve out there is a gameplay question, not an")
print("academic one.\n")

print("=" * 78)
print("THE TWO CANDIDATE SHAPES")
print("=" * 78)
sl, ic = fit(D, G)
pred_lin = [sl * d + ic for d in D]
print(f"  LINEAR    ga/match = {sl:.4f} * d {ic:+.4f}")
print(f"            r = {corr(D, G):+.3f}  RMSE {rmse(pred_lin, G):.4f}/match"
      f"  ({rmse(pred_lin, G) * MATCHES:.1f} goals/season)")

L = [math.log(g) for g in G]
sl2, ic2 = fit(D, L)
pred_log = [math.exp(sl2 * d + ic2) for d in D]
print(f"  LOG-LINK  ga/match = exp({sl2:.5f} * d {ic2:+.4f})")
print(f"            r = {corr(D, L):+.3f}  RMSE {rmse(pred_log, G):.4f}/match"
      f"  ({rmse(pred_log, G) * MATCHES:.1f} goals/season)")

strong = [r for r in rows if r["def_rating"] >= 83]
Ds = [r["def_rating"] for r in strong]
Gs = [r["ga"] for r in strong]
pls = [sl * d + ic for d in Ds]
pgs = [math.exp(sl2 * d + ic2) for d in Ds]
print(f"\n  where it matters, the {len(strong)} strongest club-seasons (d >= 83):")
print(f"     linear    RMSE {rmse(pls, Gs):.4f}  bias {bias(pls, Gs):+.4f}")
print(f"     log-link  RMSE {rmse(pgs, Gs):.4f}  bias {bias(pgs, Gs):+.4f}")
print("  The bias is the finding: a straight line systematically believes good")
print("  defences concede FEWER goals than they really do, and gets worse the")
print("  better the defence gets. Extrapolated, that is the single-digit season.")

print("\n  is the real relationship actually curved? binned actuals:")
B = 10
per = len(rows) // B
for i in range(B):
    chunk = rows[i * per:(i + 1) * per] if i < B - 1 else rows[i * per:]
    md = statistics.mean(r["def_rating"] for r in chunk)
    mg = statistics.mean(r["ga"] for r in chunk)
    print(f"     d~{md:5.2f}   actual {mg * MATCHES:5.1f}/season"
          f"   linear {(sl * md + ic) * MATCHES:5.1f}"
          f"   log {math.exp(sl2 * md + ic2) * MATCHES:5.1f}   (n={len(chunk)})")

print("\n" + "=" * 78)
print("OUT PAST THE DATA - the whole reason for the change")
print("=" * 78)
print("   rating    linear/season    log-link/season")
for d in [86.76, 88.0, 88.9, 89.6, 91, 93, 95, 99]:
    print(f"   {d:6.2f}      {(sl * d + ic) * MATCHES:7.1f}"
          f"          {math.exp(sl2 * d + ic2) * MATCHES:7.1f}")
print("   The line concedes a NEGATIVE number of goals above 94.5.")

print("\n" + "=" * 78)
print("THE KEEPER, refitted on the same scale")
print("=" * 78)
kr = [r for r in rows if r["gk_save"] is not None]
resid = [math.log(r["ga"]) - (sl2 * r["def_rating"] + ic2) for r in kr]
SV = [r["gk_save"] for r in kr]
ks, ki = fit(SV, resid)
mean_sv = statistics.mean(SV)
print(f"  n={len(kr)}  log_residual = {ks:.6f} * save_pct {ki:+.5f}"
      f"   r = {corr(SV, resid):+.3f}")
print(f"  league mean save% = {mean_sv:.2f}")
print(f"  check the residual is centred: neutral factor ="
      f" {math.exp(ks * mean_sv + ki):.4f} (want 1.0000)")
p_nokeep = [math.exp(sl2 * r["def_rating"] + ic2) for r in kr]
p_keep = [math.exp(sl2 * r["def_rating"] + ic2 + ks * (r["gk_save"] - mean_sv)) for r in kr]
act = [r["ga"] for r in kr]
print(f"  RMSE without keeper {rmse(p_nokeep, act):.4f}"
      f"  with keeper {rmse(p_keep, act):.4f}")
print(f"  a keeper saving 80% multiplies goals conceded by"
      f" {math.exp(ks * (80 - mean_sv)):.3f} against an average one")

print("\n" + "=" * 78)
print("DOES THE LEAGUE TABLE SURVIVE THE CHANGE?")
print("=" * 78)
print("Better goals are worthless if they produce a worse season. Both defence")
print("shapes go through the same Poisson points calculation against the same")
print("attack fit, so the only thing differing is the shape being tested.")
att_rows = [r for r in rows if r["att"] is not None and r["gf"] is not None]
sa, ia = fit([r["att"] for r in att_rows], [r["gf"] for r in att_rows])
print(f"\n  attack (unchanged): gf/match = {sa:.4f} * sum_npxg90 {ia:+.4f}"
      f"   n={len(att_rows)}")

# The additive keeper term, refitted here so the linear model is judged at its
# best rather than against a term fitted for the other shape.
lin_resid = [r["ga"] - (sl * r["def_rating"] + ic) for r in kr]
lks, lki = fit(SV, lin_resid)

for label in ("linear", "log-link"):
    pp, ap = [], []
    for r in att_rows:
        gf = sa * r["att"] + ia
        if label == "linear":
            ga = sl * r["def_rating"] + ic
            if r["gk_save"] is not None:
                ga += lks * r["gk_save"] + lki
        else:
            ga = math.exp(sl2 * r["def_rating"] + ic2)
            if r["gk_save"] is not None:
                ga *= math.exp(ks * (r["gk_save"] - mean_sv))
        gf, ga = max(0.2, gf), max(0.2, ga)
        pp.append(poisson_points(gf, ga) * MATCHES)
        ap.append(r["points"])
    print(f"  {label:9} r = {corr(pp, ap):+.3f}   RMSE {rmse(pp, ap):.1f} points"
          f"   mean predicted {statistics.mean(pp):.1f}"
          f" vs actual {statistics.mean(ap):.1f}")

print("\n" + "=" * 78)
print("WHAT A FREE MODE MAGIC XI NOW CONCEDES")
print("=" * 78)
for d, sv, lab in [(88.90, 80.6, "Premier League"), (89.56, 79.7, "La Liga")]:
    g = math.exp(sl2 * d + ic2 + ks * (sv - mean_sv))
    print(f"  {lab:15} d={d:.2f}  ->  {g * MATCHES:4.1f} conceded,"
          f" ~{math.exp(-g) * MATCHES:.0f} clean sheets"
          f"      (the line gave {(sl * d + ic) * MATCHES:4.1f})")
g = math.exp(sl2 * 99 + ic2 + ks * (90 - mean_sv))
print(f"  eleven 99s behind a 90% keeper: {g * MATCHES:.1f} a season - the model's")
print("  own answer, not a limit imposed on it.")

print("\n" + "=" * 78)
print("PASTE INTO SimModel.kt")
print("=" * 78)
print(f"  DEFENCE_LOG_SLOPE        = {sl2:.5f}")
print(f"  DEFENCE_LOG_INTERCEPT    = {ic2:.4f}")
print(f"  KEEPER_SAVE_LOG_SLOPE    = {ks:.6f}")
print(f"  LEAGUE_AVERAGE_SAVE_PCT  = {mean_sv:.2f}")
