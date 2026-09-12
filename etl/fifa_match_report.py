"""
Reports how well the FIFA ratings table covers the players actually loaded
in Supabase, per season. Read-only.

Match cascade (strictest first), mirroring roles.match_role's approach:
  1. exact  : normalized name (long OR short form) + birth_year
  2. subset : FBref tokens are a subset of FIFA's long_name tokens, same
              birth year — Spanish compound surnames ("Iago Aspas" vs
              "Iago Aspas Juncal"). Must be a UNIQUE hit.
  3. year+-1: as above but birth_year off by one (dob/edition-boundary slop)
  4. name   : unique normalized-name match within that season, any birth year
  5. initial: FIFA short-name form "L. Messi" vs FBref "Lionel Messi"
  6. family-first: "Son Heung-min" -> FIFA's "H. Son" (East Asian rows whose
              long_name is non-Latin and gets stripped by normalization)
  7. surname: surname + exact birth year + first initial, unique only

The cascade itself lives in fifa_lookup.FifaLookup.get_with_tier() and is
NOT duplicated here — an earlier version of this report kept its own copy,
which drifted when a tier was added and then under-reported coverage.
Anything unmatched is listed so it can be fixed by hand.
"""
from __future__ import annotations

import os
import sys
from collections import defaultdict
from pathlib import Path

import requests
from dotenv import load_dotenv

from fifa_lookup import FifaLookup
from roles import _normalize

FIFA = FifaLookup()

sys.stdout.reconfigure(encoding="utf-8")  # accented names break the cp1252 console

load_dotenv(Path(__file__).parent / ".env")
SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
H = {"apikey": SERVICE_KEY, "Authorization": f"Bearer {SERVICE_KEY}"}
FIFA_DIR = Path(__file__).parent / "fifa"


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


# ---- load our side ----
leagues = {l["id"]: l["name"] for l in get_all("leagues", {"select": "id,name"})}
seasons = {s["id"]: s for s in get_all("seasons", {"select": "id,league_id,label"})}
club_seasons = {c["id"]: c for c in get_all("club_seasons", {"select": "id,season_id,club_name"})}
players = {p["id"]: p for p in get_all("players", {"select": "id,full_name,date_of_birth"})}
stats = get_all("player_season_stats", {"select": "id,player_id,club_season_id,primary_position,minutes"})

MATCH_TIERS = ["exact", "subset", "year±1", "name", "initial", "family-first", "club", "surname"]


tally: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
unmatched: dict[str, list[tuple]] = defaultdict(list)

for st in stats:
    cs = club_seasons.get(st["club_season_id"])
    if not cs:
        continue
    sea = seasons[cs["season_id"]]
    label, lg = sea["label"], leagues[sea["league_id"]]
    skey = f"{lg} {label}"
    tally[skey]["total"] += 1

    if label not in FIFA.seasons:
        tally[skey]["no_dataset"] += 1
        continue

    p = players[st["player_id"]]
    n = _normalize(p["full_name"])
    by = int(p["date_of_birth"][:4])

    hit = FIFA.get_with_tier(p["full_name"], by, label, cs["club_name"])[1]

    if hit:
        tally[skey][hit] += 1
    else:
        tally[skey]["unmatched"] += 1
        unmatched[skey].append((p["full_name"], by, cs["club_name"], st["primary_position"], st["minutes"]))

# ---- report ----
print("=" * 92)
print("FIFA RATING COVERAGE PER LEAGUE-SEASON")
print("=" * 92)
print(f"{'league-season':26s} {'rows':>5s} {'exact':>6s} {'subset':>6s} {'yr±1':>5s} {'name':>4s} {'init':>4s} {'famF':>4s} {'club':>4s} {'surn':>4s} {'MISS':>5s} {'cover':>6s}")
g_tot = g_match = g_miss = g_nodata = 0
for k in sorted(tally):
    t = tally[k]
    total = t["total"]
    matched = sum(t[m] for m in MATCH_TIERS)
    if t["no_dataset"]:
        print(f"{k:28s} {total:5d} {'—  no FIFA dataset for this season —':>50s}")
        g_nodata += total
        g_tot += total
        continue
    print(f"{k:26s} {total:5d} {t['exact']:6d} {t['subset']:6d} {t['year±1']:5d} "
          f"{t['name']:4d} {t['initial']:4d} {t['family-first']:4d} {t['club']:4d} {t['surname']:4d} "
          f"{t['unmatched']:5d} {100*matched/total:5.1f}%")
    g_tot += total
    g_match += matched
    g_miss += t["unmatched"]

covered = g_tot - g_nodata
print("-" * 92)
print(f"{'TOTAL (seasons with data)':28s} {covered:5d} matched={g_match} unmatched={g_miss}  "
      f"coverage={100*g_match/covered:.1f}%")
if g_nodata:
    print(f"{'Rows with NO dataset at all':28s} {g_nodata:5d}")

print("\n" + "=" * 92)
print("UNMATCHED PLAYERS — these need help (showing regulars first, >900 min)")
print("=" * 92)
for k in sorted(unmatched):
    regs = sorted([u for u in unmatched[k] if u[4] >= 900], key=lambda x: -x[4])
    if not regs:
        continue
    print(f"\n{k}  ({len(unmatched[k])} unmatched, {len(regs)} of them regulars)")
    for name, by, club, pos, mins in regs[:12]:
        print(f"    {name:30s} b{by}  {club:18s} {pos:7s} {mins:5d} min")

total_regs = sum(1 for k in unmatched for u in unmatched[k] if u[4] >= 900)
print(f"\nUnmatched REGULARS (>=900 min) across all seasons with data: {total_regs}")
print(f"Unmatched fringe (<900 min): {g_miss - total_regs}")
