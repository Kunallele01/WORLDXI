"""
Backfills the out-of-position data added by migration_0005:

  * player_season_stats.pos_rating_{cb,fb,dm,cm,cam,winger,st}
  * player_season_stats.position_side       (display only)
  * player_season_stats.pos_rating_edition  (provenance)
  * players.nationality                     (was 0% populated)

All of it comes from the SAME FIFA match that supplies overall_rating, via
fifa_lookup.get_profile(). No second matcher — a parallel copy of that cascade
has already drifted out of sync once on this project.

WHAT IS STORED. The raw EA positional ratings, not a computed penalty. The
penalty is a DELTA (grid[slot] - grid[natural slot]) and deltas are cheap to
compute but impossible to reinterpret; keeping the raw grid means a change to
how out-of-position play is priced does not need another ETL pass.

Dry-run by default; pass --apply to write.
"""
from __future__ import annotations

import os
import sys
from collections import Counter
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from dotenv import load_dotenv

from fifa_lookup import SLOT_KEYS, FifaLookup

APPLY = "--apply" in sys.argv
sys.stdout.reconfigure(encoding="utf-8")

load_dotenv(Path(__file__).parent / ".env")
SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
}

# Transfermarkt's 8 per-season roles -> the slot whose grid value is that
# player's natural reference point. GK is present but never used: the GK slot
# is locked both ways, so a keeper never needs an out-of-position number.
ROLE_TO_SLOT = {
    "CB": "cb", "FB": "fb", "DM": "dm", "CM": "cm",
    "CAM": "cam", "Winger": "winger", "ST": "st",
}

FIFA = FifaLookup()


def fetch(table: str, params: dict) -> list[dict]:
    out: list[dict] = []
    offset = 0
    with requests.Session() as s:
        s.headers.update(HEADERS)
        while True:
            p = dict(params, limit=1000, offset=offset)
            r = s.get(f"{SUPABASE_URL}/rest/v1/{table}", params=p, timeout=120)
            r.raise_for_status()
            batch = r.json()
            if not batch:
                return out
            out += batch
            offset += 1000


print("Loading reference data...")
players = {p["id"]: p for p in fetch("players", {"select": "id,full_name,date_of_birth,nationality"})}
club_seasons = {c["id"]: c for c in fetch("club_seasons", {"select": "id,club_name,season_id"})}
seasons = {s["id"]: s for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select": "id,player_id,club_season_id,primary_position"})
print(f"  {len(players)} players, {len(stats)} player-seasons")

stat_updates: list[tuple[int, dict]] = []
player_nationality: dict[int, str] = {}
tiers = Counter()
borrowed = 0
no_grid = 0
sides = Counter()

for s in stats:
    player = players[s["player_id"]]
    dob = (player.get("date_of_birth") or "")
    if len(dob) < 4:
        tiers["no birth year"] += 1
        continue
    cs = club_seasons[s["club_season_id"]]
    label = seasons[cs["season_id"]]["label"]

    profile, tier = FIFA.get_profile(player["full_name"], int(dob[:4]), label, cs["club_name"])
    if profile is None:
        tiers["UNMATCHED"] += 1
        continue
    tiers[tier or "?"] += 1

    payload: dict[str, object] = {"pos_rating_edition": profile.grid_edition or None}
    if profile.grid:
        for key in SLOT_KEYS:
            payload[f"pos_rating_{key}"] = profile.grid.get(key)
        if profile.grid_edition and profile.grid_edition != profile.edition:
            borrowed += 1
    else:
        no_grid += 1

    # Side is recorded only where it means something. A centre-back or striker
    # has no side, and writing 'B' for them would turn "not applicable" into
    # "plays both flanks".
    if s["primary_position"] in ("FB", "Winger"):
        payload["position_side"] = profile.side
        sides[profile.side or "unknown"] += 1

    stat_updates.append((s["id"], payload))

    if profile.nationality and not player.get("nationality"):
        player_nationality[player["id"]] = profile.nationality

print(f"\nmatch tiers: {dict(tiers.most_common())}")
print(f"  player-seasons to update: {len(stat_updates)}")
print(f"  grids borrowed from a neighbouring edition (2023/24 mostly): {borrowed}")
print(f"  matched but NO positional grid available at all: {no_grid}")
print(f"  nationalities to fill: {len(player_nationality)}")
print(f"  side resolution (FB/Winger only): {dict(sides.most_common())}")

if stat_updates:
    sample = stat_updates[:3]
    print("\nsample payloads:")
    for sid, payload in sample:
        print(f"   stat {sid}: {payload}")

if not APPLY:
    print("\nDRY RUN — pass --apply to write.")
    raise SystemExit(0)

# PostgREST cannot bulk-upsert these tables (id is GENERATED ALWAYS), so each
# row is a PATCH. Threaded because 5,700 sequential round trips takes minutes.
#
# RETRIED, because a single dropped connection used to abandon the whole run.
# At 11k rows and 16 threads this reliably hit a ConnectTimeout partway
# through, leaving the table half-updated — recoverable only because the
# script is idempotent, but each retry re-sent every row. A PATCH of one row
# by id is safe to repeat, so retry it here instead of restarting the job.
def patch(table: str, row_id: int, payload: dict, attempts: int = 4) -> None:
    for attempt in range(attempts):
        try:
            with requests.Session() as s:
                s.headers.update(HEADERS)
                r = s.patch(
                    f"{SUPABASE_URL}/rest/v1/{table}",
                    params={"id": f"eq.{row_id}"},
                    json=payload,
                    timeout=60,
                )
                r.raise_for_status()
                return
        except (requests.ConnectionError, requests.Timeout):
            if attempt == attempts - 1:
                raise
            time.sleep(2 ** attempt)


print(f"\nWriting {len(stat_updates)} player_season_stats rows...")
with ThreadPoolExecutor(max_workers=6) as pool:
    list(pool.map(lambda a: patch("player_season_stats", a[0], a[1]), stat_updates))

print(f"Writing {len(player_nationality)} nationalities...")
with ThreadPoolExecutor(max_workers=6) as pool:
    list(pool.map(lambda a: patch("players", a[0], {"nationality": a[1]}), player_nationality.items()))

print("Done.")
