"""
Checks the staged 2014/15-2018/19 files before anybody loads them.

Reads etl/staged/ only. Writes nothing, anywhere.

The four checks, and what each would catch:

  1 TABLES     champion, points and last place against recorded history.
                Catches a wrong season, a wrong competition, or fixtures
                silently missing from the source.
  2 IDENTITY   Understat's goal count against FBref's, per matched row.
                Both count league goals for the same man, so a large
                disagreement means the matcher attached the wrong person's
                xG — the one failure mode a looser name match can introduce
                and the reason that match is confined to a confirmed club.
  3 SCORERS    the golden boot per league-season, by name and tally.
                An independent read on whether the spine itself is right.
  4 STRENGTH   mean rating of a club's best XI against where it actually
                finished. Should be strongly negative (better squads finish
                higher); the modern seasons sit near -0.87.
"""
from __future__ import annotations

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from stage_seasons_2015_2019 import find_in_club, load_understat, norm, same_club  # noqa: E402

STAGED = Path(__file__).parent / "staged"

# Recorded history. Deliberately typed out from the record rather than derived
# from the same file being checked — a check that reads its own input proves
# only that the file is self-consistent.
KNOWN = {
    ("PL", "2014/15"): ("Chelsea", 87, "QPR", 30),
    ("PL", "2015/16"): ("Leicester City", 81, "Aston Villa", 17),
    ("PL", "2016/17"): ("Chelsea", 93, "Sunderland", 24),
    ("PL", "2017/18"): ("Manchester City", 100, "West Brom", 31),
    ("PL", "2018/19"): ("Manchester City", 98, "Huddersfield", 16),
    ("LaLiga", "2014/15"): ("Barcelona", 94, "Cordoba", 20),
    ("LaLiga", "2015/16"): ("Barcelona", 91, "Levante", 32),
    ("LaLiga", "2016/17"): ("Real Madrid", 93, "Granada", 20),
    ("LaLiga", "2017/18"): ("Barcelona", 93, "Malaga", 20),
    ("LaLiga", "2018/19"): ("Barcelona", 87, "Rayo Vallecano", 32),
}
GOLDEN_BOOT = {
    ("PL", "2014/15"): ("Sergio Aguero", 26),
    ("PL", "2015/16"): ("Harry Kane", 25),
    ("PL", "2016/17"): ("Harry Kane", 29),
    ("PL", "2017/18"): ("Mohamed Salah", 32),
    ("PL", "2018/19"): ("Pierre-Emerick Aubameyang", 22),
    ("LaLiga", "2014/15"): ("Cristiano Ronaldo", 48),
    ("LaLiga", "2015/16"): ("Luis Suarez", 40),
    ("LaLiga", "2016/17"): ("Lionel Messi", 37),
    ("LaLiga", "2017/18"): ("Lionel Messi", 34),
    ("LaLiga", "2018/19"): ("Lionel Messi", 36),
}
SEASONS = [f"{y}/{str(y + 1)[2:]}" for y in range(2014, 2019)]
LEAGUES = {"PL": "Premier League", "LaLiga": "La Liga"}


def tag(league: str, season: str) -> str:
    return f"{league}_{season.replace('/', '-')}"


def read(prefix: str, league: str, season: str) -> list[dict]:
    with open(STAGED / f"{prefix}_{tag(league, season)}.tsv", encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def check_tables() -> int:
    print("1 TABLES")
    bad = 0
    for (league, season), (champ, pts, last, last_pts) in KNOWN.items():
        rows = read("table", league, season)
        top, bottom = rows[0], rows[-1]
        ok = (norm(top["club_name"]) == norm(champ) and int(top["points"]) == pts
              and norm(bottom["club_name"]) == norm(last) and int(bottom["points"]) == last_pts)
        bad += not ok
        mark = "ok " if ok else "BAD"
        print(f"   {mark} {league:7s} {season}  {top['club_name']} {top['points']} / "
              f"{bottom['club_name']} {bottom['points']}")
    return bad


def check_identity() -> int:
    print("\n2 IDENTITY (Understat goals vs FBref goals on matched rows)")
    by_name, by_club = load_understat()
    checked = wrong = off_by_small = 0
    worst: list[tuple] = []
    for league, comp in LEAGUES.items():
        for season in SEASONS:
            for r in read("playerseasons", league, season):
                if not r["xg"]:
                    continue
                us = None
                for cand in by_name.get((season, comp, norm(r["full_name"])), []):
                    if "," not in cand["team_title"] and same_club(cand["team_title"], r["club_name"]):
                        us = cand
                        break
                if us is None:
                    for key, club_rows in by_club.items():
                        if key[0] == season and key[1] == comp and same_club(key[2], r["club_name"]):
                            us = find_in_club(r["full_name"], club_rows, int(r["goals"]))
                            break
                if us is None:
                    continue
                checked += 1
                delta = abs(int(us["goals"]) - int(r["goals"]))
                if delta > 2:
                    wrong += 1
                    worst.append((season, r["full_name"], r["club_name"], r["goals"], us["goals"]))
                elif delta:
                    off_by_small += 1
    print(f"   checked {checked} matched rows")
    print(f"   within 2 goals: {checked - wrong} ({(checked - wrong) * 100 / max(checked, 1):.2f}%)"
          f"  [{off_by_small} differ by 1-2, normal provider disagreement]")
    print(f"   MORE than 2 apart: {wrong}")
    for w in worst[:10]:
        print(f"      {w[0]} {w[1][:24]:26s} {w[2][:16]:17s} fbref {w[3]} vs understat {w[4]}")
    return wrong


def check_scorers() -> int:
    print("\n3 SCORERS (golden boot)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            rows = read("playerseasons", league, season)
            # A transfer splits a man across two rows; the boot is his total.
            totals: dict[str, int] = defaultdict(int)
            for r in rows:
                totals[r["full_name"]] += int(r["goals"])
            top = max(totals.items(), key=lambda kv: kv[1])
            want_name, want_goals = GOLDEN_BOOT[(league, season)]
            ok = norm(top[0]) == norm(want_name) and top[1] == want_goals
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  {top[0]} {top[1]}"
                  + ("" if ok else f"   expected {want_name} {want_goals}"))
    return bad


def check_strength() -> int:
    print("\n4 STRENGTH (mean best-XI rating vs final position)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            players = read("playerseasons", league, season)
            table = {r["club_name"]: int(r["final_position"]) for r in read("table", league, season)}
            by_club: dict[str, list[int]] = defaultdict(list)
            for r in players:
                if r["overall_rating"] and int(r["minutes"]) >= 450:
                    by_club[r["club_name"]].append(int(r["overall_rating"]))
            xs, ys = [], []
            for club, ratings in by_club.items():
                if club not in table or len(ratings) < 11:
                    continue
                xs.append(statistics.mean(sorted(ratings, reverse=True)[:11]))
                ys.append(table[club])
            if len(xs) < 10:
                print(f"   BAD {league:7s} {season}  only {len(xs)} clubs scored")
                bad += 1
                continue
            r = statistics.correlation(xs, ys)
            ok = r <= -0.6
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  r = {r:+.3f}   "
                  f"best XI {min(xs):.1f}-{max(xs):.1f}")
    return bad


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    failures = check_tables() + check_identity() + check_scorers() + check_strength()
    print(f"\n{'ALL CHECKS PASSED' if not failures else f'{failures} CHECK(S) FAILED'}")
    raise SystemExit(1 if failures else 0)
