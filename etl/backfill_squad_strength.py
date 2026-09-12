"""
Backfills club_seasons.squad_strength (added in migration_0004) from the
ratings already in player_season_stats.

Re-run this after ANY change to overall_rating — the draft's tier caps read
strength through the club_season_draft_pool view, so a stale strength would
silently mis-tier squads and make the caps guard the wrong teams.

Dry-run by default; pass --apply to write.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import requests
from dotenv import load_dotenv

from squad_strength import load_squads

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

squads = load_squads()
print(f"computed squad_strength for {len(squads)} club_seasons")

vals = sorted(s["strength"] for s in squads.values())
n = len(vals)
print(f"  range {vals[0]} .. {vals[-1]}   quartile bounds: "
      f"Q1<={vals[n//4]}  median={vals[n//2]}  Q4>={vals[(3*n)//4]}")

if not APPLY:
    print("\nDRY RUN — pass --apply to write. Sample:")
    for cs_id, s in list(squads.items())[:5]:
        print(f"   club_season {cs_id}: {s['club']} {s['label']} -> {s['strength']}")
    raise SystemExit(0)

with requests.Session() as session:
    session.headers.update(HEADERS)
    written = 0
    for cs_id, s in squads.items():
        r = session.patch(
            f"{SUPABASE_URL}/rest/v1/club_seasons",
            params={"id": f"eq.{cs_id}"},
            json={"squad_strength": s["strength"]},
            timeout=30,
        )
        r.raise_for_status()
        written += 1
print(f"wrote squad_strength for {written} club_seasons")
