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

csv.field_size_limit(10_000_000)


@dataclass(frozen=True)
class Player:
    sofifa_id: str
    name: str
    overall: int
    rating: float          # on the FIFA 17+ scale
    positions: tuple[str, ...]
    club: str

    @property
    def is_keeper(self) -> bool:
        return "GK" in self.positions


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
    """edition -> nationality -> players, best first."""
    table: dict[str, dict[str, list[Player]]] = defaultdict(lambda: defaultdict(list))
    with open(NORMALIZED, encoding="utf-8", errors="replace", newline="") as fh:
        for row in csv.DictReader(fh):
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
            ))
    for per_nation in table.values():
        for players in per_nation.values():
            players.sort(key=lambda p: -p.rating)
    return table


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
