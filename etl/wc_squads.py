"""
The rated player pool behind one nation in one World Cup, with a fallback.

THE PROBLEM THIS SOLVES. A nation's anchor edition (the N+1 rule, WC 2026 ->
FC 26) sometimes holds too few players to field anyone, because EA only rates
players in leagues it licenses and that coverage moved over the years — Japan
is 9 players in FIFA 07 and 472 in FIFA 19; Saudi Arabia is zero until FIFA 15
adds 362. An old squad is really just its Europe-based players.

WHY THAT MOSTLY DOESN'T MATTER, AND WHERE IT DOES. Real results stand for every
match the user is not in, so an opponent only needs a strength if he can
actually play them — and nearly every thin nation is itself a bottom-of-group
side the user REPLACES (2006 Costa Rica/Iran/Japan/Saudi Arabia, 2010 Korea
DPR, 2014 Iran). Across all six editions only **Egypt and Iran in 2026** are
both unfieldable and reachable as opponents.

THE FALLBACK. A nation holding fewer than eleven players in its anchor takes
the nearest edition that holds eleven, and the borrowed edition is RECORDED on
the pool rather than disguised as the anchor — the same choice FifaLookup
makes when it borrows a positional grid. Ties go to the later edition, whose
rosters are the more complete. Approved by the user 2026-09-12.

Editions 10 and 12-14 are never borrowed from: they are the club-era scrape and
cover the Premier League and La Liga only, so they describe a nation's players
at two European clubs rather than its national pool.

Ratings are also returned on the modern scale via rating_scale, because the
draft pool deliberately mixes eras and a FIFA 07 number is not comparable to an
FC 26 one until it is corrected.
"""
from __future__ import annotations

import csv
import sys
from collections import defaultdict
import dataclasses
from dataclasses import dataclass
from pathlib import Path

import wc_fixtures
import wc_nations
from rating_scale import to_modern_scale

HERE = Path(__file__).parent
NORMALIZED = HERE / "fifa" / "fifa_ratings_normalized.csv"

#: Below this a nation cannot be fielded at all, so the fallback engages.
MIN_XI = 11
#: The club-era scrape: Premier League and La Liga only, never a national pool.
CLUB_ONLY_EDITIONS = frozenset({"10", "12", "13", "14"})

#: A nation's rated pool is not a squad — England 2022 holds 1,629 players,
#: down to League Two. The draft shows a real-sized squad instead: 23 through
#: 2018 and 26 from 2022, matching what FIFA actually allowed.
SQUAD_SIZE = {2006: 23, 2010: 23, 2014: 23, 2018: 23, 2022: 26, 2026: 26}
KEEPERS = 3
#: Picking purely by rating can leave a squad with one full-back and nine
#: central midfielders, which the draft cannot fill a back four from. These
#: floors mirror how real squads are built; the rest goes to the best remaining.
LINE_MINIMUM = {"DEF": 8, "MID": 7, "ATT": 5}
LINE_OF = {
    "GK": "GK",
    "CB": "DEF", "LB": "DEF", "RB": "DEF", "LWB": "DEF", "RWB": "DEF",
    "CDM": "MID", "CM": "MID", "CAM": "MID", "LM": "MID", "RM": "MID",
    "ST": "ATT", "CF": "ATT", "LW": "ATT", "RW": "ATT",
}

#: EA position -> the engine's eight roles, as etl/roles.py groups them. Shared
#: by the squads, the calibration and the app export so they cannot disagree.
EA_ROLE = {
    "GK": "GK", "CB": "CB", "LB": "FB", "RB": "FB", "LWB": "FB", "RWB": "FB",
    "CDM": "DM", "CM": "CM", "CAM": "CAM", "LM": "Winger", "RM": "Winger",
    "LW": "Winger", "RW": "Winger", "LF": "ST", "RF": "ST", "CF": "ST", "ST": "ST",
}
#: The positional grid columns, by role.
GRID_COLUMNS = {"CB": "pos_cb", "FB": "pos_fb", "DM": "pos_dm", "CM": "pos_cm",
                "CAM": "pos_cam", "Winger": "pos_winger", "ST": "pos_st"}

#: How far a positional grid may be borrowed from, in editions. See _with_grid.
MAX_GRID_BORROW_GAP = 2

csv.field_size_limit(10_000_000)


@dataclass(frozen=True)
class Player:
    sofifa_id: str
    name: str
    overall: int
    rating: float          # on the FIFA 17+ scale
    positions: tuple[str, ...]
    club: str
    birth_year: int | None = None
    #: EA's positional grid for this PERSON, as the change from his primary
    #: role to each role ((role, delta), ...). None when no edition of him has
    #: one, in which case the rating-banded fallback prices him instead.
    grid: tuple[tuple[str, int], ...] | None = None
    #: The edition the grid came from — his own, or the nearest one borrowed.
    grid_edition: str | None = None
    #: The edition this rating itself comes from.
    edition: str | None = None

    @property
    def primary_role(self) -> str | None:
        return EA_ROLE.get(self.positions[0]) if self.positions else None

    @property
    def grid_map(self) -> dict[str, int] | None:
        return dict(self.grid) if self.grid is not None else None

    @property
    def is_keeper(self) -> bool:
        return "GK" in self.positions

    @property
    def line(self) -> str:
        """GK / DEF / MID / ATT, from the primary (first-listed) position."""
        return LINE_OF.get(self.positions[0] if self.positions else "", "MID")


@dataclass(frozen=True)
class NationPool:
    team: str              # as the fixtures spell it
    nation: str | None     # as the ratings table spells it
    anchor_edition: str
    source_edition: str | None
    players: tuple[Player, ...]

    @property
    def borrowed(self) -> bool:
        return self.source_edition is not None and self.source_edition != self.anchor_edition

    @property
    def fieldable(self) -> bool:
        return len(self.players) >= MIN_XI


def _load() -> dict[str, dict[str, list[Player]]]:
    """edition -> nationality -> players, best first, each with his positional grid."""
    table: dict[str, dict[str, list[Player]]] = defaultdict(lambda: defaultdict(list))
    # sofifa id -> edition -> absolute grid. sofifa's id is the same PERSON in
    # every edition, so a grid from another year is still his own.
    grids: dict[str, dict[str, dict[str, int]]] = defaultdict(dict)
    with open(NORMALIZED, encoding="utf-8", errors="replace", newline="") as fh:
        for row in csv.DictReader(fh):
            if row.get("pos_cb") and row.get("sofifa_id", "").isdigit():
                try:
                    grids[row["sofifa_id"]][row["edition"]] = {
                        role: int(float(row[col])) for role, col in GRID_COLUMNS.items()
                    }
                except (TypeError, ValueError):
                    pass
            nation = (row.get("nationality") or "").strip()
            overall = (row.get("overall") or "").strip()
            if not nation or not overall.isdigit():
                continue
            edition = row["edition"]
            table[edition][nation].append(Player(
                sofifa_id=row.get("sofifa_id", ""),
                name=row.get("display_name", ""),
                overall=int(overall),
                rating=to_modern_scale(edition, int(overall)),
                positions=tuple(p for p in (row.get("positions") or "").split(";") if p),
                club=row.get("club", ""),
                birth_year=int(row["birth_year"]) if (row.get("birth_year") or "").isdigit() else None,
            ))
    for edition, per_nation in table.items():
        for nation, players in per_nation.items():
            per_nation[nation] = sorted((_with_grid(p, edition, grids) for p in players), key=lambda p: -p.rating)
    return table


def _with_grid(player: Player, edition: str, grids: dict[str, dict[str, dict[str, int]]]) -> Player:
    """
    The player with EA's positional grid attached: his own edition's where it
    has one, otherwise the nearest edition that does (later winning ties).

    WHY PER PLAYER. A single table of median costs priced every defensive
    midfielder the same at full-back, -2, and that median is dominated by
    low-rated players. EA's own grid for Rodri says -7 (87 at DM, 80 at
    full-back), and the magic XI put him at left-back on the strength of the -2.
    Half of all World Cup squad players have their own grid and another 38% have
    one in a nearby edition, so real grids price 87% of them.

    WHY DELTAS, NOT RATINGS. A borrowed grid comes from a different edition and
    so a different rating scale and a different point in a career. The CHANGE
    from his primary role to each other role is what carries across; the rating
    it is applied to stays this edition's own. The primary role is THIS
    edition's, so a player who has since moved position is priced from where he
    plays here.

    BORROWED ONLY FROM NEARBY EDITIONS. A grid describes a player at one point
    in a career, and position moves over a career. Measured against every real
    World Cup match: borrowing from any distance predicts results worse
    (log-loss 0.9559) than borrowing within two editions (0.9500), while own
    grids alone give 0.9505 and the rating-banded fallback 0.9450 — so beyond
    two editions the fallback is the better description of the player. FC 26
    Rodri borrows FIFA 24 (two apart) and keeps his -7 at full-back; a 2006
    player no longer borrows a grid from FIFA 15.
    """
    primary = player.primary_role
    by_edition = grids.get(player.sofifa_id)
    player = dataclasses.replace(player, edition=edition)
    if primary is None or primary == "GK" or not by_edition:
        return player
    near = [e for e in by_edition if abs(int(e) - int(edition)) <= MAX_GRID_BORROW_GAP]
    if not near:
        return player
    source = min(near, key=lambda e: (abs(int(e) - int(edition)), -int(e)))
    absolute = by_edition[source]
    reference = absolute[primary]
    grid = tuple((role, absolute[role] - reference) for role in GRID_COLUMNS)
    return dataclasses.replace(player, grid=grid, grid_edition=source)


_TABLE: dict[str, dict[str, list[Player]]] | None = None


def ratings_table() -> dict[str, dict[str, list[Player]]]:
    global _TABLE
    if _TABLE is None:
        _TABLE = _load()
    return _TABLE


def _search_order(anchor: str, available: list[str]) -> list[str]:
    """The anchor first, then outward by distance, later edition winning ties."""
    return sorted(available, key=lambda e: (abs(int(e) - int(anchor)), -int(e)))


def pool_for(wc_edition: int, team: str) -> NationPool:
    table = ratings_table()
    anchor = wc_fixtures.RATING_EDITION[wc_edition]
    usable = [e for e in table if e not in CLUB_ONLY_EDITIONS]

    # The nation is named per edition: EA shipped "Czechia" in FIFA 07 and FC 26
    # but "Czech Republic" in FIFA 15, so it is resolved against each edition.
    for edition in _search_order(anchor, usable):
        nation = wc_nations.to_rating_nation(team, set(table[edition]))
        if nation is None:
            continue
        players = table[edition][nation]
        if len(players) >= MIN_XI:
            return NationPool(team, nation, anchor, edition, tuple(players))

    # Nobody anywhere reaches an XI: report the anchor's own holding, whatever
    # it is, so the caller sees the truth rather than an empty pool.
    nation = wc_nations.to_rating_nation(team, set(table[anchor]))
    players = tuple(table[anchor][nation]) if nation else ()
    return NationPool(team, nation, anchor, anchor if nation else None, players)


def national_squad(players: tuple[Player, ...], size: int) -> tuple[Player, ...]:
    """
    A real-sized squad from a nation's rated pool: the best KEEPERS goalkeepers,
    each outfield line's floor from LINE_MINIMUM, then the best of the rest.
    A pool no bigger than `size` is returned whole. Missing keepers or a thin
    line are made up from the best remaining players rather than left empty.
    """
    ranked = sorted(players, key=lambda p: -p.rating)
    if len(ranked) <= size:
        return tuple(ranked)
    outfield = [p for p in ranked if p.line != "GK"]
    chosen = [p for p in ranked if p.line == "GK"][:KEEPERS]
    for line, floor in LINE_MINIMUM.items():
        chosen += [p for p in outfield if p.line == line][:floor]
    taken = {id(p) for p in chosen}
    for p in outfield + ranked:
        if len(chosen) >= size:
            break
        if id(p) not in taken:
            chosen.append(p)
            taken.add(id(p))
    return tuple(sorted(chosen, key=lambda p: -p.rating))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    matches = wc_fixtures.load_all()
    borrowed_total, broken_total = 0, 0

    for wc_edition in wc_fixtures.EDITIONS:
        teams = sorted(wc_fixtures.participants(matches, wc_edition))
        pools = [pool_for(wc_edition, t) for t in teams]
        borrowed = [p for p in pools if p.borrowed]
        broken = [p for p in pools if not p.fieldable]
        keeperless = [p for p in pools if p.fieldable and not any(x.is_keeper for x in p.players)]
        borrowed_total += len(borrowed)
        broken_total += len(broken)

        print(f"\n=== World Cup {wc_edition} (anchor FIFA "
              f"{wc_fixtures.RATING_EDITION[wc_edition]}) ===")
        print(f"  {len(teams)} nations, {len(borrowed)} borrowed, {len(broken)} still unfieldable")
        for p in sorted(borrowed, key=lambda x: x.team):
            print(f"    {p.team}: borrowed FIFA {p.source_edition} "
                  f"({len(p.players)} players, best {p.players[0].rating:.0f})")
        for p in broken:
            print(f"    STILL BROKEN {p.team}: {len(p.players)} players")
        if keeperless:
            print(f"    no rated keeper (not a blocker): {[p.team for p in keeperless]}")

    print("\n" + "=" * 60)
    print(f"{borrowed_total} nation-editions borrowed, {broken_total} still unfieldable")


if __name__ == "__main__":
    main()
