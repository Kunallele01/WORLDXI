"""
Measures what each position actually contributes, so the engine cannot be
fooled by an XI that could never exist.

THE PROBLEM THIS SOLVES. Attack was shown to be additive: summing an XI's npxG
per 90 predicts the team's real goals at r=+0.864. But that holds for REAL XIs,
which contain one striker and four defenders. Draft eleven strikers and a naive
sum predicts five goals a game, because nothing in the sum knows that ten of
them are standing where a striker's chances do not occur.

A position is a share of the team's play, not just a label. A striker gets the
shots because he is where the shots happen; put a centre-back there and he gets
those chances instead — worse, but not zero. Put the striker at centre-back and
he stops getting them at all. So the engine has to evaluate a player IN THE SLOT
HE OCCUPIES, and that needs per-slot baselines measured from real football.

Run: python sim_position_shares.py
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

MIN_MINUTES = 900          # a rate over less than ten games is noise
ROLES = ["GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST"]


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


def per90(p: dict, key: str) -> float | None:
    v = p.get(key)
    if v is None or not p["minutes"]:
        return None
    return float(v) * 90.0 / p["minutes"]


print("Loading...")
stats = [s for s in fetch("player_season_stats", {"select":
    "primary_position,minutes,overall_rating,npxg,xa,key_passes,shots,goals,"
    "assists,tackles,interceptions,save_pct"})
    if (s["minutes"] or 0) >= MIN_MINUTES and s["primary_position"]]
print(f"  {len(stats)} player-seasons at {MIN_MINUTES}+ minutes\n")

by_role = defaultdict(list)
for s in stats:
    by_role[s["primary_position"]].append(s)

print("=" * 92)
print("PER-90 OUTPUT BY POSITION — the baseline a slot contributes")
print("=" * 92)
print(f"{'role':8}{'n':>6}{'npxG/90':>22}{'xA/90':>22}{'shots/90':>16}")
print(f"{'':8}{'':>6}{'p25   med   p75':>22}{'p25   med   p75':>22}{'med':>16}")
print("-" * 92)
baselines = {}
for role in ROLES:
    rows = by_role.get(role, [])
    if not rows:
        continue
    def q(key):
        vals = sorted(v for v in (per90(r, key) for r in rows) if v is not None)
        if not vals:
            return (0.0, 0.0, 0.0)
        return (vals[len(vals)//4], vals[len(vals)//2], vals[(3*len(vals))//4])
    npx, xa, sh = q("npxg"), q("xa"), q("shots")
    baselines[role] = {"npxg": npx[1], "xa": xa[1], "shots": sh[1], "n": len(rows)}
    print(f"{role:8}{len(rows):>6}"
          f"{npx[0]:>8.3f}{npx[1]:>7.3f}{npx[2]:>7.3f}"
          f"{xa[0]:>8.3f}{xa[1]:>7.3f}{xa[2]:>7.3f}"
          f"{sh[1]:>16.2f}")

total_npxg = sum(b["npxg"] for r, b in baselines.items() if r != "GK")
print(f"\nA notional XI of median players at each position would produce")
print(f"about {total_npxg:.2f} npxG per match from ten outfielders,")
print(f"against a real league average of roughly 1.38 goals per match.")

print("\n" + "=" * 92)
print("SHARE OF ATTACKING OUTPUT BY POSITION")
print("=" * 92)
print("""
This is the number that stops eleven strikers scoring five a game. A slot is
worth a share of the team's chances; a player in it can exceed or fall short of
what that slot normally yields, but he cannot conjure chances that the position
does not receive.
""")
for role in ROLES:
    if role == "GK" or role not in baselines:
        continue
    share = baselines[role]["npxg"] / total_npxg * 100
    print(f"  {role:8} {share:5.1f}% of a team's npxG   (median {baselines[role]['npxg']:.3f}/90)")

print("\n" + "=" * 92)
print("DEFENSIVE BASELINE BY POSITION — rating, not volume")
print("=" * 92)
print("""
Volume is inverted on this data and cannot be used as quality. What a slot needs
is a sense of how much DEFENSIVE RESPONSIBILITY it carries, so a striker placed
at centre-back is judged against what a centre-back is asked to do.
""")
print(f"{'role':8}{'n':>6}{'median OVR':>13}{'tackles+int /90':>18}")
for role in ROLES:
    rows = by_role.get(role, [])
    if not rows:
        continue
    ovr = statistics.median(r["overall_rating"] for r in rows)
    ti = [((r["tackles"] or 0) + (r["interceptions"] or 0)) * 90.0 / r["minutes"] for r in rows]
    print(f"{role:8}{len(rows):>6}{ovr:>13.0f}{statistics.median(ti):>18.2f}")

print("\nWrite these into the engine as SlotBaseline; see sim_design.md.")
