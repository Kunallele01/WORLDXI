"""
One-off data-health check across all loaded league-seasons in Supabase.
Read-only: uses the service_role key but only ever GETs.
"""
from __future__ import annotations

import os
from collections import Counter, defaultdict
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent / ".env")

SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {"apikey": SERVICE_KEY, "Authorization": f"Bearer {SERVICE_KEY}"}


def get_all(table: str, params: dict) -> list[dict]:
    """Paginates through Supabase REST (default 1000-row cap)."""
    out = []
    offset = 0
    page = 1000
    while True:
        p = dict(params)
        p["limit"] = page
        p["offset"] = offset
        r = requests.get(f"{SUPABASE_URL}/rest/v1/{table}", headers=HEADERS, params=p)
        r.raise_for_status()
        batch = r.json()
        out.extend(batch)
        if len(batch) < page:
            break
        offset += page
    return out


print("Fetching leagues/seasons/club_seasons/players/player_season_stats...\n")

leagues = get_all("leagues", {"select": "id,name,country"})
seasons = get_all("seasons", {"select": "id,league_id,label,start_year"})
club_seasons = get_all("club_seasons", {"select": "id,season_id,club_name"})
players = get_all("players", {"select": "id,full_name,date_of_birth"})
stats = get_all("player_season_stats", {
    "select": "id,player_id,club_season_id,primary_position,overall_rating,minutes,appearances,goals,assists,saves"
})
fixtures = get_all("fixtures", {"select": "id,season_id"})

league_by_id = {l["id"]: l for l in leagues}
season_by_id = {s["id"]: s for s in seasons}
cs_by_id = {c["id"]: c for c in club_seasons}
player_by_id = {p["id"]: p for p in players}

# ---------------------------------------------------------------- 1. COUNTS
print("=" * 70)
print("1. COUNTS PER LEAGUE-SEASON")
print("=" * 70)
cs_count = Counter()
fx_count = Counter()
stat_count = Counter()
for cs in club_seasons:
    cs_count[cs["season_id"]] += 1
for f in fixtures:
    fx_count[f["season_id"]] += 1
for st in stats:
    cs = cs_by_id.get(st["club_season_id"])
    if cs:
        stat_count[cs["season_id"]] += 1

for s in sorted(seasons, key=lambda s: (league_by_id[s["league_id"]]["name"], s["start_year"])):
    lg = league_by_id[s["league_id"]]["name"]
    sid = s["id"]
    print(f"  {lg:16s} {s['label']:8s} clubs={cs_count[sid]:3d}  fixtures={fx_count[sid]:4d}  stat_rows={stat_count[sid]:4d}")

print(f"\n  TOTAL: {len(leagues)} leagues, {len(seasons)} seasons, {len(club_seasons)} club_seasons, "
      f"{len(fixtures)} fixtures, {len(players)} unique players, {len(stats)} player_season_stats rows")

# ---------------------------------------------------------- 2. DUPLICATE ID
print("\n" + "=" * 70)
print("2. DUPLICATE PLAYER IDENTITY CHECK (same name+birth_year, different id)")
print("=" * 70)
by_identity = defaultdict(list)
for p in players:
    by_identity[(p["full_name"], p["date_of_birth"])].append(p["id"])
dupes = {k: v for k, v in by_identity.items() if len(v) > 1}
if dupes:
    print(f"  FOUND {len(dupes)} duplicate identities:")
    for (name, dob), ids in list(dupes.items())[:20]:
        print(f"    {name} ({dob}) -> ids {ids}")
else:
    print("  None found — every (full_name, date_of_birth) maps to exactly one players.id.")

# ------------------------------------------------- 3. MID-SEASON TRANSFERS
print("\n" + "=" * 70)
print("3. MID-SEASON / MULTI-CLUB PLAYER-SEASON ROWS (same player, same season, 2+ clubs)")
print("=" * 70)
# group stats rows by (player_id, season_id)
rows_by_player_season = defaultdict(list)
for st in stats:
    cs = cs_by_id.get(st["club_season_id"])
    if not cs:
        continue
    rows_by_player_season[(st["player_id"], cs["season_id"])].append(st)

multi = {k: v for k, v in rows_by_player_season.items() if len(v) > 1}
print(f"  {len(multi)} player-seasons have 2+ club rows in the same season (mid-season transfers).")
print("  Sample:")
count = 0
for (pid, sid), rows in multi.items():
    if count >= 10:
        break
    name = player_by_id[pid]["full_name"]
    lbl = season_by_id[sid]["label"]
    lg = league_by_id[season_by_id[sid]["league_id"]]["name"]
    clubs = [cs_by_id[r["club_season_id"]]["club_name"] for r in rows]
    mins = [r["minutes"] for r in rows]
    print(f"    {name:28s} {lg} {lbl}: {list(zip(clubs, mins))}")
    count += 1

# also check cross-league same-season (rare: mid-season move PL<->LaLiga)
rows_by_player_year = defaultdict(set)
for st in stats:
    cs = cs_by_id.get(st["club_season_id"])
    if not cs:
        continue
    sea = season_by_id[cs["season_id"]]
    rows_by_player_year[(st["player_id"], sea["start_year"])].add(sea["league_id"])
cross_league = {k: v for k, v in rows_by_player_year.items() if len(v) > 1}
print(f"\n  {len(cross_league)} players appear in BOTH leagues in the same start_year (cross-league moves).")
for (pid, yr), lids in list(cross_league.items())[:10]:
    name = player_by_id[pid]["full_name"]
    lgs = [league_by_id[l]["name"] for l in lids]
    print(f"    {name:28s} {yr}: {lgs}")

# ------------------------------------------------------- 4. RATING SPREAD
print("\n" + "=" * 70)
print("4. OVERALL_RATING DISTRIBUTION (all seasons combined, by position group)")
print("=" * 70)
by_pos = defaultdict(list)
for st in stats:
    r = st.get("overall_rating")
    if r is None:
        continue
    pos = (st.get("primary_position") or "?")
    by_pos[pos].append(r)

for pos in sorted(by_pos):
    vals = sorted(by_pos[pos])
    n = len(vals)
    mean = sum(vals) / n
    p10 = vals[int(n * 0.10)]
    p50 = vals[int(n * 0.50)]
    p90 = vals[min(n - 1, int(n * 0.90))]
    print(f"  {pos:8s} n={n:4d}  min={vals[0]:3d}  p10={p10:3d}  median={p50:3d}  "
          f"mean={mean:5.1f}  p90={p90:3d}  max={vals[-1]:3d}")

null_ratings = sum(1 for st in stats if st.get("overall_rating") is None)
print(f"\n  NULL overall_rating rows: {null_ratings} / {len(stats)}")

# --------------------------------------------------- 5. SANITY / OUTLIERS
print("\n" + "=" * 70)
print("5. SUSPICIOUS VALUES")
print("=" * 70)
bad = []
for st in stats:
    if st["minutes"] and st["minutes"] > 3600:
        bad.append(("minutes>3600", st))
    if st["appearances"] and st["appearances"] > 38:
        bad.append(("apps>38 (hard cap for a 20-team league)", st))
    if st.get("overall_rating") is not None and not (0 <= st["overall_rating"] <= 99):
        bad.append(("rating out of range", st))
if bad:
    print(f"  {len(bad)} suspicious rows:")
    for tag, st in bad[:20]:
        name = player_by_id.get(st["player_id"], {}).get("full_name", "?")
        print(f"    [{tag}] {name} row_id={st['id']}")
else:
    print("  None found.")

print("\nDone.")
