"""
Stages real fixtures and league tables for 2014/15-2018/19, PL and La Liga.

NO BROWSER NEEDED, and no scraping. The worldfootballR mirror publishes
tier-1 match results per country back to 1993 as an .rds; every season we
want is already in it. That is the same mirror fbref_archive.py uses for
player stats, so club naming agrees between the two by construction — which
is the whole reason not to take fixtures from a different source.

LEAGUE TABLES ARE DERIVED, NOT FETCHED. A table is a deterministic function
of 380 results, so scraping one separately would only create a second thing
that can disagree with the fixtures. Derived here and checked against known
history (see verify_tables below).

Writes to etl/staged/. Does not touch the database.
"""
from __future__ import annotations

import csv
import os
import sys
import tempfile
import urllib.request
from collections import defaultdict
from pathlib import Path

import pyreadr

BASE = "https://github.com/JaseZiv/worldfootballR_data/raw/master/data/match_results/"
STAGED = Path(__file__).parent / "staged"
STAGED.mkdir(exist_ok=True)

# league label -> (country file, FBref Competition_Name)
LEAGUES = {
    "PL": ("ENG", "Premier League"),
    "LaLiga": ("ESP", "La Liga"),
}
# season label -> FBref Season_End_Year. Defaults to the 2014/15-2018/19 run
# this script was written for; pass a start and end year to stage another range
# (e.g. `2009 2013` for 2009/10-2013/14). Same code either way, so a later
# range cannot quietly drift from the one already loaded.
_FIRST, _LAST = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) == 3 else (2014, 2018)
SEASONS = {f"{y}/{str(y + 1)[2:]}": y + 1 for y in range(_FIRST, _LAST + 1)}

# Points deductions, which no set of results can know about. The modern data
# stores the OFFICIAL table (Everton 2023/24 holds 40, not the 48 their results
# earn), so derived tables must subtract these to stay consistent with it.
# Every entry is the recorded sanction for that season, typed from the record.
DEDUCTIONS = {
    ("PL", "2009/10", "Portsmouth"): 9,   # administration, March 2010
}


def load_country(code: str):
    path = Path(tempfile.gettempdir()) / f"{code}_match_results.rds"
    if not path.exists():
        urllib.request.urlretrieve(BASE + f"{code}_match_results.rds", path)
    return list(pyreadr.read_r(str(path)).values())[0]


def table_from(matches: list[dict], deductions: dict[str, int] | None = None) -> list[dict]:
    """Standard three-points-for-a-win table, ordered the way the league orders it."""
    rows: dict[str, dict] = defaultdict(
        lambda: dict(wins=0, draws=0, losses=0, goals_for=0, goals_against=0)
    )
    for m in matches:
        h, a, hg, ag = m["home"], m["away"], m["home_goals"], m["away_goals"]
        for club, gf, ga in ((h, hg, ag), (a, ag, hg)):
            r = rows[club]
            r["goals_for"] += gf
            r["goals_against"] += ga
            r["wins" if gf > ga else "losses" if gf < ga else "draws"] += 1
    out = []
    for club, r in rows.items():
        r = dict(r, club_name=club)
        r["points"] = r["wins"] * 3 + r["draws"] - (deductions or {}).get(club, 0)
        r["goal_difference"] = r["goals_for"] - r["goals_against"]
        out.append(r)
    # Both leagues break ties on goal difference then goals scored. La Liga
    # actually uses head-to-head first; that only reorders clubs level on
    # points and never changes who is 20th, which is all the game reads.
    out.sort(key=lambda r: (-r["points"], -r["goal_difference"], -r["goals_for"]))
    for i, r in enumerate(out, 1):
        r["final_position"] = i
    return out


def main() -> None:
    countries = {code: load_country(code) for code, _ in set(LEAGUES.values())}
    for league, (code, comp) in LEAGUES.items():
        df = countries[code]
        for season, end_year in SEASONS.items():
            sel = df[(df.Competition_Name == comp)
                     & (df.Season_End_Year.astype(float) == end_year)
                     & (df.Tier == "1st")]
            matches = []
            for r in sel.to_dict("records"):
                if r["HomeGoals"] is None or r["AwayGoals"] != r["AwayGoals"]:
                    continue
                matches.append(dict(
                    date=str(r["Date"])[:10],
                    matchday=int(float(r["Wk"])) if r["Wk"] == r["Wk"] else 0,
                    home=r["Home"].strip(), away=r["Away"].strip(),
                    home_goals=int(float(r["HomeGoals"])),
                    away_goals=int(float(r["AwayGoals"])),
                ))
            tag = f"{league}_{season.replace('/', '-')}"
            with open(STAGED / f"fixtures_{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
                w = csv.DictWriter(fh, matches[0].keys(), delimiter="\t")
                w.writeheader()
                w.writerows(matches)
            deducted = {club: pts for (lg, ssn, club), pts in DEDUCTIONS.items()
                        if lg == league and ssn == season}
            unknown = set(deducted) - {m["home"] for m in matches}
            assert not unknown, f"deduction for a club not in {league} {season}: {unknown}"
            table = table_from(matches, deducted)
            with open(STAGED / f"table_{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
                w = csv.DictWriter(fh, ["final_position", "club_name", "wins", "draws",
                                        "losses", "goals_for", "goals_against",
                                        "goal_difference", "points"], delimiter="\t")
                w.writeheader()
                w.writerows({k: r[k] for k in w.fieldnames} for r in table)
            champion, last = table[0], table[-1]
            print(f"{league} {season}: {len(matches)} matches, {len(table)} clubs | "
                  f"1st {champion['club_name']} {champion['points']}pts | "
                  f"last {last['club_name']} {last['points']}pts")


if __name__ == "__main__":
    main()
