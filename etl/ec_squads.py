"""
Rosters for the European Cup draft pool: every group-stage club's players for a
season, from the EA FC ratings already in fifa/fifa_ratings_normalized.csv.

    python ec_squads.py 2023        write ec/ec_players_2023.tsv and print a report

WHERE A ROSTER COMES FROM. WHO is at a club comes from the edition RELEASED IN
SEPTEMBER OF THAT SEASON (2023/24 -> FIFA 24): it is a snapshot of the squad as
it began the season, so Mbappe is at PSG. The N+1 edition (EA FC 25) is a
snapshot a year later and lists him at Real Madrid, so it cannot say who played
for whom in 2023/24.

WHERE A RATING COMES FROM. The project's own finding (fifa_ratings.py) is that
edition N+1 describes season N best: EA grades on what a player just did. So a
player's rating is his FC 25 rating where he can be linked to a FC 25 row, and
his FC 24 rating where he cannot, the same preferred-then-fallback convention
fifa_lookup.SEASON_EDITIONS uses for the leagues. Each row records which.

LINKING FC 24 -> FC 25. FC 25 rows carry placeholder ids (fc25-N), so players
are linked on shared name tokens + nationality + birth year (+-1, the FC 25 year
is derived from an age). Exactly one best candidate links; a tie or no match
leaves the FC 24 rating in place. Nobody is dropped for failing to link.

RED STAR BELGRADE has no roster: EA does not license the Serbian league in any
edition, so no source here has its players. It never needs one to take part,
only to be drafted from (it finished bottom of its 2023/24 group, which makes it
a club the user's XI can replace, and then it is the user who plays its games).
"""
from __future__ import annotations

import csv
import sys
from collections import Counter, defaultdict
from pathlib import Path

from roles import _normalize

HERE = Path(__file__).parent
NORMALIZED = HERE / "fifa" / "fifa_ratings_normalized.csv"
OUT_DIR = HERE / "ec"

#: canonical (Wikipedia-based) club name -> club name inside the FC 24 rows
FC24_CLUB = {
    "Bayern Munich": "FC Bayern München", "Copenhagen": "København",
    "Manchester United": "Manchester United", "Galatasaray": "Galatasaray",
    "Arsenal": "Arsenal", "PSV Eindhoven": "PSV", "Lens": "Lens", "Sevilla": "Sevilla",
    "Napoli": "Napoli", "Real Madrid": "Real Madrid", "Braga": "Sporting Braga",
    "Union Berlin": "FC Union Berlin", "Real Sociedad": "Real Sociedad", "Inter Milan": "Inter",
    "Benfica": "Benfica", "Red Bull Salzburg": "Salzburg", "Atlético Madrid": "Atlético Madrid",
    "Lazio": "Lazio", "Feyenoord": "Feyenoord", "Celtic": "Celtic",
    "Borussia Dortmund": "Borussia Dortmund", "Paris Saint-Germain": "Paris Saint Germain",
    "AC Milan": "Milan", "Newcastle United": "Newcastle United", "Manchester City": "Manchester City",
    "RB Leipzig": "RB Leipzig", "Young Boys": "Young Boys", "Barcelona": "FC Barcelona",
    "Porto": "Porto", "Shakhtar Donetsk": "Shakhtar Donetsk", "Antwerp": "Antwerp",
}
NO_ROSTER = {"Red Star Belgrade"}

SEASON_EDITIONS = {2023: ("24", "25")}      # season -> (roster edition, rating edition)
GRID_ROLES = {"cb": "CB", "fb": "FB", "dm": "DM", "cm": "CM", "cam": "CAM", "winger": "Winger", "st": "ST"}
#: EA position -> the engine's roles, the same table wc_squads uses.
EA_ROLE = {
    "GK": "GK", "CB": "CB", "LB": "FB", "RB": "FB", "LWB": "FB", "RWB": "FB",
    "CDM": "DM", "CM": "CM", "CAM": "CAM", "LM": "Winger", "RM": "Winger",
    "LW": "Winger", "RW": "Winger", "LF": "ST", "RF": "ST", "CF": "ST", "ST": "ST",
}


def grid_deltas(r: dict) -> dict:
    """
    EA's positional grid as the CHANGE from the player's primary role to each role,
    which is what wc_squad_players stores and what the engine reads (see
    wc_squads._with_grid for why deltas rather than ratings). Keepers have none.
    """
    primary = EA_ROLE.get(r["positions"].split(";")[0])
    cells = {k: r.get(f"pos_{k}", "") for k in GRID_ROLES}
    key = next((k for k, role in GRID_ROLES.items() if role == primary), None)
    if key is None or any(v in ("", None) for v in cells.values()):
        return {f"grid_{k}": "" for k in GRID_ROLES}
    base = int(float(cells[key]))
    return {f"grid_{k}": int(float(v)) - base for k, v in cells.items()}


def tokens(r: dict) -> set[str]:
    names = {_normalize(r["display_name"])} | {v for v in r["norm_variants"].split(";") if v}
    return {t for n in names for t in n.split() if len(t) > 2}


def linker(rows25: list[dict]):
    idx = defaultdict(list)
    for r in rows25:
        for t in tokens(r):
            idx[(t, (r["nationality"] or "").lower())].append(r)

    def surnames(r: dict) -> set[str]:
        names = {_normalize(r["display_name"])} | {v for v in r["norm_variants"].split(";") if v}
        return {n.split()[-1] for n in names if n.split()} - {""}

    def link(r: dict):
        by, nat = int(r["birth_year"]), (r["nationality"] or "").lower()
        sur = surnames(r)
        score: dict[str, list] = {}
        for t in tokens(r):
            for c in idx.get((t, nat), []):
                # A shared first name is not identity: Elneny and Salah are both
                # Mohamed, Egyptian and born 1992. The surname has to agree too.
                if abs(int(c["birth_year"]) - by) <= 1 and sur & tokens(c):
                    score.setdefault(c["sofifa_id"], [c, 0])[1] += 1
        if not score:
            return None
        best = max(v[1] for v in score.values())
        top = [v[0] for v in score.values() if v[1] == best]
        return top[0] if len(top) == 1 else None
    return link


def build(season: int):
    roster_ed, rating_ed = SEASON_EDITIONS[season]
    rows = list(csv.DictReader(open(NORMALIZED, encoding="utf-8")))
    base = [r for r in rows if r["edition"] == roster_ed]
    link = linker([r for r in rows if r["edition"] == rating_ed])
    # The table keeps a full legal name for some players ("Carlos Henrique Venancio
    # Casimiro"); the game's own short name ("Casemiro") is what a draft card shows.
    short = {}
    csv.field_size_limit(10_000_000)
    for a in csv.DictReader(open(HERE / "archive" / "male_players.csv", encoding="utf-8")):
        if a["fifa_version"].split(".")[0] == roster_ed:
            short[a["player_id"].split(".")[0]] = a["short_name"]
    out = []
    for club, fc in FC24_CLUB.items():
        for r in (x for x in base if x["club"] == fc):
            c = link(r)
            rating, src = (c["overall"], rating_ed) if c else (r["overall"], roster_ed)
            out.append({
                "season": season, "club": club, "sofifa_id": r["sofifa_id"], "name": short.get(r["sofifa_id"], r["display_name"]),
                "birth_year": r["birth_year"], "nationality": r["nationality"],
                "positions": r["positions"], "rating": rating, "rating_edition": src,
                "fc24_rating": r["overall"], **grid_deltas(r),
            })
    return out


SQUAD_SIZE = 25
KEEPERS = 3
#: Same idea as wc_squads.LINE_MINIMUM: a pure best-25 can come out with one
#: full-back, which the draft cannot build a back four from.
LINE_MINIMUM = {"DEF": 7, "MID": 6, "ATT": 4}
LINE_OF = {"GK": "GK", "CB": "DEF", "LB": "DEF", "RB": "DEF", "LWB": "DEF", "RWB": "DEF",
           "CDM": "MID", "CM": "MID", "CAM": "MID", "LM": "MID", "RM": "MID",
           "ST": "ATT", "CF": "ATT", "LW": "ATT", "RW": "ATT"}


def line(p: dict) -> str:
    return LINE_OF.get(p["positions"].split(";")[0], "MID")


def cap(players: list[dict]) -> list[dict]:
    """Best 25 by rating: 3 keepers, then the line minimums, then the best of the rest."""
    ranked = sorted(players, key=lambda p: (-int(p["rating"]), p["name"]))
    chosen = [p for p in ranked if line(p) == "GK"][:KEEPERS]
    for ln, n in LINE_MINIMUM.items():
        chosen += [p for p in ranked if line(p) == ln and p not in chosen][:n]
    chosen += [p for p in ranked if p not in chosen and line(p) != "GK"][: SQUAD_SIZE - len(chosen)]
    # A club with exactly 25 players and a fourth keeper is one short without this.
    chosen += [p for p in ranked if p not in chosen][: SQUAD_SIZE - len(chosen)]
    return sorted(chosen, key=lambda p: (-int(p["rating"]), p["name"]))


def main() -> None:
    season = int(sys.argv[1]) if len(sys.argv) > 1 else 2023
    everyone = build(season)
    by_club = defaultdict(list)
    for p in everyone:
        by_club[p["club"]].append(p)
    players = [p for c in by_club.values() for p in cap(c)]
    assert all(len([p for p in players if p["club"] == c]) == SQUAD_SIZE for c in by_club if len(by_club[c]) >= SQUAD_SIZE)
    OUT_DIR.mkdir(exist_ok=True)
    path = OUT_DIR / f"ec_players_{season}.tsv"
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(players[0]), delimiter="\t")
        w.writeheader(); w.writerows(players)
    by = defaultdict(list)
    for p in players:
        by[p["club"]].append(p)
    assert set(by) == set(FC24_CLUB), set(FC24_CLUB) - set(by)
    print(f"{len(players)} players, {len(by)} clubs -> {path.name}  (no roster: {sorted(NO_ROSTER)})")
    print(f"{'club':22}{'n':>4}{'GK':>4}{'FC25':>6}{'avg':>7}  top rated")
    for club in sorted(by):
        ps = by[club]
        gk = sum(1 for p in ps if p["positions"].split(";")[0] == "GK")
        n25 = sum(1 for p in ps if p["rating_edition"] == "25")
        top = sorted(ps, key=lambda p: -int(p["rating"]))[:3]
        avg = sum(int(p["rating"]) for p in sorted(ps, key=lambda p: -int(p["rating"]))[:14]) / 14
        print(f"{club:22}{len(ps):>4}{gk:>4}{n25:>6}{avg:>7.1f}  " + ", ".join(f"{p['name']} {p['rating']}" for p in top))
        assert gk >= 2 and len(ps) >= 20, (club, len(ps), gk)


if __name__ == "__main__":
    main()
