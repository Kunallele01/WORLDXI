"""
Measures the relationships a match engine can honestly be built on.

The point of this file is that NOTHING in the simulation should be a
coefficient someone liked the look of. We hold 200 real club-seasons with known
goals scored and conceded, so every quantity the engine uses can be checked
against what those teams actually did — and anything that fails to predict
reality gets thrown out rather than tuned until it looks plausible.

Three questions, in order of how much they constrain the design:

  1. ATTACK. Does a squad's expected-goals output actually add up? If the sum
     of an XI's npxG per 90 approximates the team's real goals per match, the
     engine can be additive over players. If not, it needs a team-level term
     and the whole shape changes.

  2. DEFENCE. What predicts goals conceded? Defensive box-score volume is
     already proven to be INVERSELY related to quality on this data (centre-
     backs at the best defences make fewer tackles and interceptions than
     those at the worst), so the candidate is defender rating.

  3. GOALKEEPING. How much of the variance in goals conceded is the keeper
     rather than the defence in front of him?

Run: python sim_calibration.py
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
#: Enough of a season to have a stable per-90 rate.
MIN_MINUTES = 450


def fetch(table: str, params: dict) -> list[dict]:
    out: list[dict] = []
    offset = 0
    with requests.Session() as s:
        s.headers.update(HEADERS)
        while True:
            p = dict(params, limit=1000, offset=offset)
            r = s.get(f"{SUPABASE_URL}/rest/v1/{table}", params=p, timeout=180)
            r.raise_for_status()
            batch = r.json()
            if not batch:
                return out
            out += batch
            offset += 1000


def corr(xs, ys) -> float:
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((a - mx) * (b - my) for a, b in zip(xs, ys))
    den = (sum((a - mx) ** 2 for a in xs) * sum((b - my) ** 2 for b in ys)) ** 0.5
    return num / den if den else 0.0


def fit(xs, ys) -> tuple[float, float]:
    """Least-squares slope and intercept, so a relationship can be USED and not
    merely admired."""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((a - mx) * (b - my) for a, b in zip(xs, ys))
    den = sum((a - mx) ** 2 for a in xs)
    slope = num / den if den else 0.0
    return slope, my - slope * mx


def rmse(pred, actual) -> float:
    return (sum((p - a) ** 2 for p, a in zip(pred, actual)) / len(pred)) ** 0.5


print("Loading...")
clubs = {c["id"]: c for c in fetch("club_seasons", {
    "select": "id,club_name,season_id,goals_for,goals_against,points,final_position"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select":
    "club_season_id,primary_position,minutes,overall_rating,goals,assists,shots,"
    "shots_on_target,xg,npxg,xa,key_passes,tackles,interceptions,save_pct,clean_sheets"})

squads: dict[int, list[dict]] = defaultdict(list)
for s in stats:
    if (s["minutes"] or 0) >= MIN_MINUTES:
        squads[s["club_season_id"]].append(s)
print(f"  {len(clubs)} club-seasons, {sum(len(v) for v in squads.values())} qualifying players\n")


def per90(p: dict, key: str) -> float:
    v = p.get(key)
    return (float(v) * 90.0 / p["minutes"]) if v is not None and p["minutes"] else 0.0


# ---------------------------------------------------------------- 1. ATTACK
print("=" * 78)
print("1. ATTACK — does an XI's expected goals add up to the team's real output?")
print("=" * 78)
print("""
An XI is picked as the 11 highest-minute players, which is the closest thing to
"the team that actually played" that the data supports. If summing their npxG
per 90 lands near the team's real goals per match, the engine can treat attack
as additive over players.
""")

rows = []
for cs_id, players in squads.items():
    club = clubs.get(cs_id)
    if not club or club.get("goals_for") is None:
        continue
    xi = sorted(players, key=lambda p: -p["minutes"])[:11]
    if len(xi) < 11 or any(p["npxg"] is None for p in xi):
        continue
    rows.append({
        "club": club["club_name"],
        "season": seasons[club["season_id"]],
        "sum_npxg90": sum(per90(p, "npxg") for p in xi),
        "sum_xa90": sum(per90(p, "xa") for p in xi),
        "sum_shots90": sum(per90(p, "shots") for p in xi),
        "goals_per_match": club["goals_for"] / MATCHES,
        "goals_for": club["goals_for"],
        "ga": club["goals_against"],
    })
print(f"usable club-seasons: {len(rows)}")

x = [r["sum_npxg90"] for r in rows]
y = [r["goals_per_match"] for r in rows]
slope, intercept = fit(x, y)
pred = [slope * v + intercept for v in x]
print(f"\n  sum(npxG/90 of the XI)  vs  goals per match")
print(f"     correlation r = {corr(x, y):+.3f}")
print(f"     fit: goals/match = {slope:.3f} x sum_npxg90 + {intercept:+.3f}")
print(f"     RMSE {rmse(pred, y):.3f} goals/match  ({rmse(pred, y)*MATCHES:.1f} goals/season)")
print(f"     mean sum_npxg90 {statistics.mean(x):.2f} vs mean goals/match {statistics.mean(y):.2f}")

x2 = [r["sum_shots90"] for r in rows]
print(f"\n  sum(shots/90 of the XI)  vs  goals per match     r = {corr(x2, y):+.3f}")
x3 = [r["sum_npxg90"] + r["sum_xa90"] for r in rows]
print(f"  sum(npxG + xA per 90)    vs  goals per match     r = {corr(x3, y):+.3f}")

print("\n  worst misses (where an additive model would be most wrong):")
errs = sorted(zip(rows, pred, y), key=lambda t: -abs(t[1] - t[2]))[:5]
for r, p, a in errs:
    print(f"     {r['club'][:20]:20} {r['season']}  predicted {p*MATCHES:5.1f} actual {a*MATCHES:5.1f} goals")


# --------------------------------------------------------------- 2. DEFENCE
print("\n" + "=" * 78)
print("2. DEFENCE — what predicts goals conceded?")
print("=" * 78)
print("""
Defensive box-score volume is already known to be inverted on this data, so the
honest candidates are player RATING and the goalkeeper's save percentage. Each
is measured separately before being combined, so it is visible which one is
carrying the relationship.
""")

drows = []
for cs_id, players in squads.items():
    club = clubs.get(cs_id)
    if not club or club.get("goals_against") is None:
        continue
    keepers = [p for p in players if p["primary_position"] == "GK"]
    backs = [p for p in players if p["primary_position"] in ("CB", "FB")]
    mids = [p for p in players if p["primary_position"] in ("DM", "CM")]
    if not keepers or len(backs) < 4:
        continue
    gk = max(keepers, key=lambda p: p["minutes"])
    back4 = sorted(backs, key=lambda p: -p["minutes"])[:4]
    mid3 = sorted(mids, key=lambda p: -p["minutes"])[:3]
    drows.append({
        "club": club["club_name"],
        "season": seasons[club["season_id"]],
        "def_ovr": statistics.mean(p["overall_rating"] for p in back4),
        "gk_ovr": gk["overall_rating"],
        "gk_save": float(gk["save_pct"]) if gk["save_pct"] is not None else None,
        "mid_ovr": statistics.mean(p["overall_rating"] for p in mid3) if mid3 else None,
        "unit_ovr": statistics.mean([p["overall_rating"] for p in back4] + [gk["overall_rating"]]),
        "ga_per_match": club["goals_against"] / MATCHES,
        "ga": club["goals_against"],
    })
print(f"usable club-seasons: {len(drows)}")

ga = [r["ga_per_match"] for r in drows]
for label, key in (("mean OVR of the back four", "def_ovr"),
                   ("goalkeeper OVR", "gk_ovr"),
                   ("back four + keeper OVR", "unit_ovr")):
    v = [r[key] for r in drows]
    s, i = fit(v, ga)
    p = [s * t + i for t in v]
    print(f"  {label:28} r = {corr(v, ga):+.3f}   RMSE {rmse(p, ga)*MATCHES:5.1f} goals/season")

with_save = [r for r in drows if r["gk_save"] is not None]
if with_save:
    v = [r["gk_save"] for r in with_save]
    g = [r["ga_per_match"] for r in with_save]
    print(f"  {'goalkeeper save %':28} r = {corr(v, g):+.3f}   (n={len(with_save)})")

mids_ok = [r for r in drows if r["mid_ovr"] is not None]
if mids_ok:
    v = [r["mid_ovr"] for r in mids_ok]
    g = [r["ga_per_match"] for r in mids_ok]
    print(f"  {'midfield OVR':28} r = {corr(v, g):+.3f}   (n={len(mids_ok)})")


# ------------------------------------------------------------ 3. GOALKEEPER
print("\n" + "=" * 78)
print("3. GOALKEEPER — how much is his own doing?")
print("=" * 78)
print("""
Save percentage is the obvious lever but it is contaminated: a keeper behind a
dominant defence faces fewer but better chances and looks worse than he is.
What matters for the engine is whether save% adds anything ONCE the defence in
front of him is accounted for.
""")
if with_save:
    # Residual goals conceded after the back-four/keeper rating fit, against
    # save% — if save% still correlates, it is carrying its own information.
    v = [r["unit_ovr"] for r in with_save]
    g = [r["ga_per_match"] for r in with_save]
    s, i = fit(v, g)
    resid = [a - (s * t + i) for t, a in zip(v, g)]
    sv = [r["gk_save"] for r in with_save]
    print(f"  residual GA after rating   vs save %   r = {corr(sv, resid):+.3f}")
    print(f"  (a clearly negative r means a good save % still saves goals the")
    print(f"   rating model did not already explain)")

print("\nDone. These numbers decide the engine's shape; see sim_design.md.")
