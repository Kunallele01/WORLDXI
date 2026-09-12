"""
Pulls per-player xG data from Understat.

WHY UNDERSTAT. FBref stripped its Opta-derived columns retroactively, so xG,
xA and key passes are no longer obtainable there for ANY season (see
fbref_archive.py). Understat has its own model and still publishes all of it,
for the big five leagues back to 2014.

This is the only stat source that covers ALL FIVE of our loaded seasons, which
makes it the floor a match engine can safely depend on: the richer FBref
archive stops at 2021/22, and anything the simulation reads from that would
quietly disadvantage 2022/23 and 2023/24 players for having thinner data
rather than for being worse.

THE ENDPOINT. One POST returns a whole league-season:

    POST https://understat.com/main/getPlayersStats/
    body: league=EPL&season=2021        (La_liga, Bundesliga, Serie_A, Ligue_1)

`season` is the START year, so 2021 means 2021/22.

Two traps, both of which cost an attempt:
  - The response is GZIPPED and served as text/javascript regardless of
    Accept-Encoding, so urllib hands back bytes that look like a binary blob.
    It is not bot protection and no browser is needed; it just has to be
    decompressed.
  - The JSON is `{"success":true,"players":[...]}` — players at the TOP LEVEL,
    not under `response`. Reading the wrong path returns an empty list, which
    looks exactly like rate limiting and invites the wrong fix.
"""
from __future__ import annotations

import csv
import gzip
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).parent / "understat"

#: Understat league code -> our league name.
LEAGUES = {"EPL": "Premier League", "La_liga": "La Liga"}

#: Understat season (start year) -> our season label.
SEASONS = {
    "2019": "2019/20",
    "2020": "2020/21",
    "2021": "2021/22",
    "2022": "2022/23",
    "2023": "2023/24",
}

FIELDS = ["player_name", "team_title", "position", "time", "games", "goals", "assists",
          "xG", "xA", "npxG", "npg", "shots", "key_passes", "xGChain", "xGBuildup"]


def fetch(league: str, season: str) -> list[dict]:
    body = urllib.parse.urlencode({"league": league, "season": season}).encode()
    req = urllib.request.Request(
        "https://understat.com/main/getPlayersStats/",
        data=body,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/140.0 Safari/537.36",
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Requested-With": "XMLHttpRequest",
            "Referer": f"https://understat.com/league/{league}/{season}",
        },
    )
    raw = urllib.request.urlopen(req, timeout=90).read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    payload = json.loads(raw.decode("utf-8", "replace"))
    return payload.get("players", [])


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    rows: list[list] = []
    for league in LEAGUES:
        for season in SEASONS:
            players = fetch(league, season)
            for p in players:
                rows.append([LEAGUES[league], SEASONS[season]] + [p.get(f, "") for f in FIELDS])
            print(f"  {LEAGUES[league]:15} {SEASONS[season]}: {len(players)} players")
            # Courtesy delay. Understat is a free site run by one person.
            time.sleep(1.0)

    dest = OUT / "understat_players.tsv"
    with open(dest, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t")
        w.writerow(["league", "season"] + FIELDS)
        w.writerows(rows)
    print(f"\nwrote {dest} — {len(rows)} player-seasons")
