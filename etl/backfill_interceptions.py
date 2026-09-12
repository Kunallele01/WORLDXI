"""
Fills interceptions for 2022/23 and 2023/24 from the live FBref scrape.

WHY THIS EXISTS SEPARATELY. Those two seasons are outside the pre-strip archive
(see fbref_archive.py), so they carried interceptions of ZERO for every player
— non-null, so a fill-rate check reported the column as 100% complete while
every value was wrong. That is worse than missing: null would have made the gap
obvious, whereas zeros silently told any consumer that nobody intercepted
anything for two whole seasons.

FBref still publishes interceptions and tackles-won live even though it has
stripped everything else, so this is recoverable. Source file is produced by a
browser scrape of the four league-seasons; see
etl/fbref_live/interceptions_2022_2024.tsv.

Dry-run by default; pass --apply to write.
"""
from __future__ import annotations

import csv
import os
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from dotenv import load_dotenv

from fifa_lookup import clubs_agree
from roles import _normalize

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
SOURCE = Path(__file__).parent / "fbref_live" / "interceptions_2022_2024.tsv"
SEASONS = {"2022/23", "2023/24"}


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


players = {p["id"]: p for p in fetch("players", {"select": "id,full_name"})}
club_seasons = {c["id"]: c for c in fetch("club_seasons", {"select": "id,club_name,season_id"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label"})}
stats = fetch("player_season_stats", {"select": "id,player_id,club_season_id,minutes,interceptions"})

index: dict[tuple[str, str], list[dict]] = defaultdict(list)
for s in stats:
    label = seasons[club_seasons[s["club_season_id"]]["season_id"]]
    if label not in SEASONS:
        continue
    index[(_normalize(players[s["player_id"]]["full_name"]), label)].append({
        "stat_id": s["id"],
        "club": club_seasons[s["club_season_id"]]["club_name"],
        "name": players[s["player_id"]]["full_name"],
        "minutes": s["minutes"] or 0,
        "current": s["interceptions"],
    })

rows = list(csv.DictReader(open(SOURCE, encoding="utf-8"), delimiter="\t"))
updates: dict[int, int] = {}
unmatched: list[str] = []
for r in rows:
    cands = index.get((_normalize(r["player"]), r["season"]), [])
    if len(cands) > 1:
        # A player can hold two rows in one season after a mid-season move;
        # the club decides which one this line belongs to.
        cands = [c for c in cands if clubs_agree(c["club"], r["team"])] or cands
    if len(cands) != 1:
        unmatched.append(f"{r['player']} ({r['team']}, {r['season']})")
        continue
    try:
        updates[cands[0]["stat_id"]] = int(r["interceptions"] or 0)
    except ValueError:
        unmatched.append(f"{r['player']} — unparseable value {r['interceptions']!r}")

print(f"source rows: {len(rows)}")
print(f"  matched:   {len(updates)} ({len(updates)*100//len(rows)}%)")
print(f"  unmatched: {len(unmatched)}")
for u in unmatched[:10]:
    print(f"     {u}")
nonzero = sum(1 for v in updates.values() if v > 0)
print(f"\nvalues to write: {nonzero} non-zero, {len(updates)-nonzero} genuine zeros")

if not APPLY:
    print("\nDRY RUN — pass --apply to write.")
    raise SystemExit(0)


def patch(item):
    stat_id, value = item
    with requests.Session() as s:
        s.headers.update(HEADERS)
        r = s.patch(f"{SUPABASE_URL}/rest/v1/player_season_stats",
                    params={"id": f"eq.{stat_id}"},
                    json={"interceptions": value}, timeout=60)
        r.raise_for_status()


print(f"\nWriting {len(updates)} rows...")
with ThreadPoolExecutor(max_workers=16) as pool:
    list(pool.map(patch, updates.items()))
print("Done.")
