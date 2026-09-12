"""
Pulls Understat player-seasons for 2014/15-2018/19, PL and La Liga.

Separate from understat.py rather than a flag on it: that script is a
working, already-run fetch for the five modern seasons and its output is
what backfill_rich_stats.py reads. This writes a DIFFERENT file so neither
can clobber the other, and reuses its fetch() so there is only one place
that knows the gzip/`players` quirks.

2013/14 is deliberately absent — Understat's history starts at 2014/15;
a request for season=2013 returns an empty list, not an error.
"""
from __future__ import annotations

import csv
import sys
import time
from pathlib import Path

from understat import FIELDS, LEAGUES, OUT, fetch

SEASONS = {str(y): f"{y}/{str(y + 1)[2:]}" for y in range(2014, 2019)}

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
            time.sleep(1.5)   # courtesy: free site, one maintainer

    dest = OUT / "understat_players_2015_2019.tsv"
    with open(dest, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t")
        w.writerow(["league", "season"] + FIELDS)
        w.writerows(rows)
    print(f"\nwrote {dest} — {len(rows)} player-seasons")
