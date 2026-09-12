"""
Checks the staged 2009/10-2013/14 files before anybody loads them.

Reads etl/staged/ only. Writes nothing, anywhere.

Reuses verify_staged_2015_2019's TABLES and STRENGTH checks against this
range's own typed history, and replaces the two checks that cannot apply:

  1 TABLES     champion, points and last place against recorded history,
               including Portsmouth's nine-point deduction in 2009/10.
  2 PROXY      there is no Understat for these seasons, so the identity check
               has nothing to compare. Instead: a club's summed proxy npxG
               must track the goals it actually scored (r >= 0.80 per season),
               and every outfield regular must carry a value. A broken
               coefficient or a mis-joined shots column fails this at once.
  3 SCORERS    top scorer by name and tally. Written fresh rather than reused,
               because 2010/11 is a genuine tie (Berbatov and Tevez, 20 each)
               and the old check assumes one winner.
  4 STRENGTH   best-XI rating against final position, r <= -0.6.
  5 ANCHOR     share of 900-minute regulars with a FIFA rating, and of
               outfield regulars with a positional grid. Old editions publish
               no grid, so it is borrowed by sofifa id from FIFA 15-18; anyone
               who retired before FIFA 15 has none, and this reports how many.
"""
from __future__ import annotations

import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import verify_staged_2015_2019 as base  # noqa: E402
from stage_seasons_2015_2019 import norm  # noqa: E402

SEASONS = [f"{y}/{str(y + 1)[2:]}" for y in range(2009, 2014)]
LEAGUES = base.LEAGUES

# Recorded history, typed from the record. Club names are spelled the way the
# FBref archive spells them, since that is what the staged files contain.
KNOWN = {
    ("PL", "2009/10"): ("Chelsea", 86, "Portsmouth", 19),
    ("PL", "2010/11"): ("Manchester Utd", 80, "West Ham", 33),
    ("PL", "2011/12"): ("Manchester City", 89, "Wolves", 25),
    ("PL", "2012/13"): ("Manchester Utd", 89, "QPR", 25),
    ("PL", "2013/14"): ("Manchester City", 86, "Cardiff City", 30),
    ("LaLiga", "2009/10"): ("Barcelona", 99, "Xerez", 34),
    ("LaLiga", "2010/11"): ("Barcelona", 96, "Almeria", 30),
    ("LaLiga", "2011/12"): ("Real Madrid", 100, "Racing Sant", 27),
    ("LaLiga", "2012/13"): ("Barcelona", 100, "Zaragoza", 34),
    ("LaLiga", "2013/14"): ("Atletico Madrid", 90, "Betis", 25),
}
TOP_SCORERS = {
    ("PL", "2009/10"): ({"Didier Drogba"}, 29),
    ("PL", "2010/11"): ({"Dimitar Berbatov", "Carlos Tevez"}, 20),
    ("PL", "2011/12"): ({"Robin van Persie"}, 30),
    ("PL", "2012/13"): ({"Robin van Persie"}, 26),
    ("PL", "2013/14"): ({"Luis Suarez"}, 31),
    ("LaLiga", "2009/10"): ({"Lionel Messi"}, 34),
    ("LaLiga", "2010/11"): ({"Cristiano Ronaldo"}, 40),
    ("LaLiga", "2011/12"): ({"Lionel Messi"}, 50),
    ("LaLiga", "2012/13"): ({"Lionel Messi"}, 46),
    ("LaLiga", "2013/14"): ({"Cristiano Ronaldo"}, 31),
}


def check_proxy() -> int:
    print("\n2 PROXY (summed proxy npxG of a club vs the goals it scored)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            players = base.read("playerseasons", league, season)
            gf = {r["club_name"]: int(r["goals_for"]) for r in base.read("table", league, season)}
            by_club: dict[str, float] = defaultdict(float)
            missing = 0
            for r in players:
                if r["primary_position"] != "GK" and int(r["minutes"]) >= 900 and r["npxg"] == "":
                    missing += 1
                if r["npxg"] != "":
                    by_club[r["club_name"]] += float(r["npxg"])
            clubs = [c for c in gf if c in by_club]
            rr = statistics.correlation([by_club[c] for c in clubs], [gf[c] for c in clubs])
            ok = rr >= 0.80 and missing == 0
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  r = {rr:+.3f}   "
                  f"proxy {sum(by_club.values()):.0f} vs {sum(gf.values())} goals   "
                  f"outfield regulars without a value: {missing}")
    return bad


def check_scorers() -> int:
    print("\n3 SCORERS (top scorer, ties allowed)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            totals: dict[str, int] = defaultdict(int)
            for r in base.read("playerseasons", league, season):
                totals[r["full_name"]] += int(r["goals"])
            best = max(totals.values())
            leaders = {n for n, g in totals.items() if g == best}
            names, goals = TOP_SCORERS[(league, season)]
            ok = best == goals and {norm(n) for n in leaders} == {norm(n) for n in names}
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  {' & '.join(sorted(leaders))} {best}"
                  + ("" if ok else f"   expected {' & '.join(sorted(names))} {goals}"))
    return bad


_EA_GROUP = {"GK": "GK", "CB": "DF", "LB": "DF", "RB": "DF", "LWB": "DF", "RWB": "DF",
             "CDM": "MF", "CM": "MF", "CAM": "MF", "LM": "MF", "RM": "MF", "LAM": "MF", "RAM": "MF",
             "LW": "FW", "RW": "FW", "ST": "FW", "CF": "FW", "LF": "FW", "RF": "FW"}


def check_identity() -> int:
    """
    The matched EA row must not contradict where FBref says the man played.

    There is no Understat to cross-check identity against for these seasons,
    so position is the independent witness. Two contradictions are treated as
    proof of a wrong person: keeper against outfielder (the game already treats
    that boundary as absolute), and a purely-defender record against a
    purely-forward EA row or vice versa. Both were found in the first staging:
    a Sporting Gijon left-back matched to a Granada striker who shared his
    name and birth year, and several one-word Spanish names ("Roberto",
    "Diego") bound to a namesake keeper or centre-back.
    """
    print("\n6 IDENTITY (FBref position vs the matched EA row)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            wrong = []
            for r in base.read("playerseasons", league, season):
                if r["rating_source"] != "fifa+form" or not r["secondary_positions"]:
                    continue
                fb = {t.strip() for t in r["fbref_position"].split(",") if t.strip()}
                ea = {_EA_GROUP[p] for p in r["secondary_positions"].split(";") if p in _EA_GROUP}
                if not fb or not ea:
                    continue
                if ("GK" in fb) != ("GK" in ea) or (fb == {"DF"} and ea == {"FW"}) or (fb == {"FW"} and ea == {"DF"}):
                    wrong.append(f"{r['full_name']} ({r['club_name']}: fbref {r['fbref_position']}, ea {r['secondary_positions']})")
            ok = not wrong
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  contradictions: {len(wrong)}")
            for w in wrong[:4]:
                print(f"         {w}")
    return bad


def check_anchor() -> int:
    """
    Every 900-minute regular must now have a rating from a known source and,
    if an outfielder, a positional grid — real or template. A regular rated
    below 55 is treated as a failure to investigate: that is the signature of
    the stats-only fallback the calibrated rating replaced (Cisse 49).
    """
    print("\n5 ANCHOR (900-minute regulars)")
    bad = 0
    for league in LEAGUES:
        for season in SEASONS:
            reg = [r for r in base.read("playerseasons", league, season) if int(r["minutes"]) >= 900]
            fifa = sum(1 for r in reg if r["rating_source"] == "fifa+form")
            calibrated = sum(1 for r in reg if r["rating_source"] == "stats_calibrated")
            unexplained = [r for r in reg if r["rating_source"] not in ("fifa+form", "stats_calibrated")]
            outfield = [r for r in reg if r["primary_position"] != "GK"]
            gridless = [r for r in outfield if r["pos_rating_cb"] == ""]
            templated = sum(1 for r in outfield if r["pos_rating_edition"] == "template")
            lowest = min(reg, key=lambda r: int(r["overall_rating"]))
            ok = (fifa / len(reg) >= 0.95 and not unexplained and not gridless
                  and int(lowest["overall_rating"]) >= 55)
            bad += not ok
            print(f"   {'ok ' if ok else 'BAD'} {league:7s} {season}  FIFA {fifa / len(reg):.0%} + "
                  f"calibrated {calibrated}   grids: template {templated}, missing {len(gridless)}   "
                  f"lowest regular {lowest['overall_rating']} ({lowest['full_name']})")
            for r in unexplained[:3]:
                print(f"         no rating source: {r['full_name']} ({r['club_name']})")
    return bad


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    base.SEASONS = SEASONS
    base.KNOWN = KNOWN
    failures = (base.check_tables() + check_proxy() + check_scorers() + base.check_strength()
                + check_anchor() + check_identity())
    print(f"\n{'ALL CHECKS PASSED' if not failures else f'{failures} CHECK(S) FAILED'}")
    raise SystemExit(1 if failures else 0)
