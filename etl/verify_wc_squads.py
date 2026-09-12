"""
Can we actually field every nation in every World Cup we ship?

The draft pool is "any nation from any edition", so a nation nobody can pick a
goalkeeper for is a hole in the mode, not a curiosity. This checks the three
things that would break it, per edition, against that edition's rating anchor:

  1. every participant resolves to a nationality in the ratings table,
  2. it holds enough players to field a real squad, and
  3. at least one of them is a goalkeeper.

2014/2018/2022 were checked before the World Cup scrape and gave 29/30/30
nations with 23+ players; they are re-run here so a regression in the shared
ratings file shows up rather than being assumed away. 2006, 2010 and 2026 are
the new ones, resting on FIFA 07, FIFA 11 and FC 26 respectively.

A "thin" squad is not automatically a defect — the draft pool spans editions,
so a 2006 Iran can be filled from another year — but it is the thing to know
before designing the draw, so it is reported rather than hidden.
"""
from __future__ import annotations

import csv
import sys
from collections import defaultdict
from pathlib import Path

import wc_fixtures
import wc_nations

HERE = Path(__file__).parent
NORMALIZED = HERE / "fifa" / "fifa_ratings_normalized.csv"

#: A real World Cup squad is 23 (26 since 2022). Below 18 we cannot field an XI
#: plus cover. Below 11 the nation cannot be fielded at all, which is the only
#: threshold that actually blocks anything: every opponent in the tournament
#: needs a strength, and a strength drawn from four players is not one.
FULL_SQUAD = 23
THIN = 18
MIN_XI = 11

csv.field_size_limit(10_000_000)


def ratings_by_nation() -> dict[str, dict[str, list[str]]]:
    """edition -> nationality -> the positions string of each player we hold."""
    out: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    with open(NORMALIZED, encoding="utf-8", errors="replace", newline="") as fh:
        for row in csv.DictReader(fh):
            nation = (row.get("nationality") or "").strip()
            if nation:
                out[row["edition"]][nation].append(row.get("positions") or "")
    return out


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    matches = wc_fixtures.load_all()
    table = ratings_by_nation()

    worst: list[str] = []
    for edition in wc_fixtures.EDITIONS:
        anchor = wc_fixtures.RATING_EDITION[edition]
        pool = table.get(anchor, {})
        known = set(pool)
        teams = sorted(wc_fixtures.participants(matches, edition))

        unresolved, sized = [], []
        for team in teams:
            nation = wc_nations.to_rating_nation(team, known)
            if nation is None:
                unresolved.append(team)
                continue
            players = pool[nation]
            keepers = sum(1 for p in players if "GK" in p.split(";"))
            sized.append((team, nation, len(players), keepers))

        full = [s for s in sized if s[2] >= FULL_SQUAD]
        thin = sorted([s for s in sized if s[2] < THIN], key=lambda s: s[2])
        unfieldable = sorted([s for s in sized if s[2] < MIN_XI], key=lambda s: s[2])
        keeperless = [s for s in sized if s[3] == 0]

        print(f"\n=== World Cup {edition}  (anchor FIFA {anchor}, "
              f"{sum(len(v) for v in pool.values())} rated players) ===")
        print(f"  {len(teams)} nations, {len(sized)} resolved, "
              f"{len(full)} with {FULL_SQUAD}+ players, {len(thin)} under {THIN}")
        if unresolved:
            print(f"  NO PLAYERS AT ALL: {unresolved}")
            for team in unresolved:
                worst.append(f"{edition} {team}: nothing in the ratings table")
        if unfieldable:
            print("  CANNOT FIELD AN XI: "
                  + ", ".join(f"{s[0]} {s[2]}" for s in unfieldable))
            for s in unfieldable:
                worst.append(f"{edition} {s[0]}: only {s[2]} players")
        if thin:
            print("  thin (pickable, but no real depth): "
                  + ", ".join(f"{t[0]} {t[2]}" for t in thin[:8]))
        if keeperless:
            # Not a blocker: the draft pool spans every nation and edition, so
            # the user's keeper never has to come from the nation he replaces.
            print(f"  no rated goalkeeper: {[s[0] for s in keeperless]}")

    print("\n" + "=" * 62)
    if worst:
        print("PROBLEMS TO DECIDE ON:")
        for line in worst:
            print("  - " + line)
    else:
        print("EVERY NATION IN EVERY SHIPPED EDITION IS FIELDABLE")


if __name__ == "__main__":
    main()
