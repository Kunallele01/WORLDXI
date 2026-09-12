"""
Exports every real club-season as an XI, so the Kotlin engine can be validated
against actual league tables in a unit test.

The Python calibration scripts prove the RELATIONSHIPS hold. This proves the
shipped code implements them: the same 156 squads go through the real engine
and the resulting league tables are compared with what actually happened. A
formula that fits and an implementation that works are different claims, and
only the second one matters to a user.

Writes app/src/test/resources/real_squads.tsv
"""
from __future__ import annotations

import csv
import os
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
OUT = Path(__file__).parent.parent / "app" / "src" / "test" / "resources" / "real_squads.tsv"


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


clubs = {c["id"]: c for c in fetch("club_seasons", {
    "select": "id,club_name,season_id,goals_for,goals_against,points"})}
seasons = {s["id"]: s["label"] for s in fetch("seasons", {"select": "id,label,league_id"})}
season_league = {s["id"]: s["league_id"] for s in fetch("seasons", {"select": "id,league_id"})}
stats = fetch("player_season_stats", {"select":
    "club_season_id,primary_position,minutes,overall_rating,npxg,save_pct,"
    "goals,assists,yellow_cards,red_cards,players(full_name)"})

squads = defaultdict(list)
for s in stats:
    if (s["minutes"] or 0) >= 450:
        squads[s["club_season_id"]].append(s)

rows = []
kept = 0
for cs_id, players in squads.items():
    c = clubs.get(cs_id)
    if not c or c.get("goals_for") is None or c.get("points") is None:
        continue
    xi = sorted(players, key=lambda p: -p["minutes"])[:11]
    if len(xi) < 11 or any(p["npxg"] is None for p in xi):
        continue
    # A real XI is the eleven who played most. Their listed positions are the
    # ones they actually filled, so every positional penalty here is zero —
    # which is the point: this validates the engine on real football before it
    # is asked about strikers at centre-back.
    kept += 1
    for p in xi:
        rows.append([
            cs_id, c["club_name"], seasons[c["season_id"]], season_league[c["season_id"]],
            c["goals_for"], c["goals_against"], c["points"],
            p["primary_position"],
            round(float(p["npxg"]) * 90.0 / p["minutes"], 5),
            p["overall_rating"],
            round(float(p["save_pct"]), 2) if p["save_pct"] is not None else "",
            (p.get("players") or {}).get("full_name") or "Unknown",
            p["goals"] or 0, p["assists"] or 0,
            p["yellow_cards"] or 0, p["red_cards"] or 0,
            p["minutes"],
        ])

OUT.parent.mkdir(parents=True, exist_ok=True)
with open(OUT, "w", encoding="utf-8", newline="") as fh:
    w = csv.writer(fh, delimiter="\t")
    w.writerow(["club_season_id", "club", "season", "league_id",
                "goals_for", "goals_against", "points",
                "role", "npxg90", "overall_rating", "save_pct",
                "name", "goals", "assists", "yellows", "reds", "minutes"])
    w.writerows(rows)

print(f"club-seasons exported: {kept}")
print(f"player rows: {len(rows)}")
print(f"wrote {OUT}")
