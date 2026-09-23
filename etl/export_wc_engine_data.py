"""
Generates the World Cup engine's static rules, and its real-tournament test
fixtures, from the data the loader already verified.

    python export_wc_engine_data.py

WRITES
  app/src/main/java/com/dreamxi/app/sim/worldcup/WcBrackets.kt
      Each edition's knockout tree: where every tie's two sides come from
      ("2nd in Group B", "winner of R16-3", "the third assigned to Group A's
      winner"). GENERATED from the real results rather than typed: every real
      knockout tie is traced back through who actually played whom, so a wrong
      slot cannot be introduced by hand.
  app/src/main/java/com/dreamxi/app/sim/worldcup/WcThirdPlaceTable2026.kt
      FIFA's 495-row Annex C table (etl/wikipedia/wc2026_third_place.tsv).
  app/src/test/resources/wc/{entries,players,matches,goals}.tsv
      Every real 2006-2026 tournament, so the Kotlin engine can be replayed
      against reality: with nobody replaced it must reproduce every group
      position, every knockout pairing and every champion exactly.

ORDER. Ties are numbered in bracket order — depth-first from the final, home
side first — so R16-1 and R16-2 feed QF-1, and a screen can draw the tree
straight from the list.

CHECKS before writing: every first-round side is a group winner, runner-up or
(2026) a qualifying third; every later side is traced to exactly one tie it
won in the previous round; the third-place match is fed by both semi-final
losers; and 2006-2022, which all used the same 32-team format, yield the same
tree shape.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import wc_fixtures
import wc_groups
import wc_nations
import wc_squads

HERE = Path(__file__).parent
APP = HERE.parent / "app" / "src"
SIM_OUT = APP / "main" / "java" / "com" / "dreamxi" / "app" / "sim" / "worldcup"
TEST_OUT = APP / "test" / "resources" / "wc"
EVENTS = HERE / "wikipedia" / "wc_events.json"
THIRDS = HERE / "wikipedia" / "wc2026_third_place.tsv"

ROUNDS = ["Round of 32", "Round of 16", "Quarter-finals", "Semi-finals", "Final"]
THIRD = "Third-place match"
SHORT = {"Round of 32": "R32", "Round of 16": "R16", "Quarter-finals": "QF",
         "Semi-finals": "SF", "Final": "F", THIRD: "3P"}


def winner(m: wc_fixtures.Match) -> str:
    if m.home_goals != m.away_goals:
        return m.home if m.home_goals > m.away_goals else m.away
    assert m.shootout_winner, f"level knockout with no shootout: {m}"
    return m.shootout_winner


def loser(m: wc_fixtures.Match) -> str:
    return m.away if winner(m) == m.home else m.home


def standings(matches, year, letters):
    """team -> (letter, position)."""
    out = {}
    for group in wc_groups.groups_of(matches, year):
        (letter,) = {letters[t] for t in group}
        for pos, s in enumerate(wc_groups.table(matches, year, group), start=1):
            out[s.team] = (letter, pos)
    return out


def bracket(matches, year, letters):
    place = standings(matches, year, letters)
    ko = [m for m in matches if m.edition == year and m.round != wc_groups.GROUP_ROUND]
    by_round = {r: [m for m in ko if m.round == r] for r in ROUNDS + [THIRD]}
    rounds = [r for r in ROUNDS if by_round[r]]
    first = rounds[0]

    def source_of(team: str, m: wc_fixtures.Match) -> str:
        idx = rounds.index(m.round)
        if idx == 0:
            letter, pos = place[team]
            if pos in (1, 2):
                return f'SlotSource.Group("{letter}", {pos})'
            if pos == 3 and year == 2026:
                opponent = m.away if team == m.home else m.home
                o_letter, o_pos = place[opponent]
                assert o_pos == 1, f"{year}: third {team} met a non-winner {opponent}"
                return f'SlotSource.ThirdAgainst("{o_letter}")'
            raise AssertionError(f"{year}: {team} ({letter}{pos}) played the {first}")
        prev = [p for p in by_round[rounds[idx - 1]] if team in (p.home, p.away)]
        assert len(prev) == 1 and winner(prev[0]) == team, f"{year}: cannot trace {team} into {m.round}"
        return prev[0]

    # Depth-first from the final, home side first, gives bracket order.
    ordered: dict[str, list] = {r: [] for r in rounds}

    def visit(m: wc_fixtures.Match):
        for team in (m.home, m.away):
            src = source_of(team, m)
            if not isinstance(src, str):
                visit(src)
        ordered[m.round].append(m)

    (final,) = by_round["Final"]
    visit(final)
    for r in rounds:
        assert len(ordered[r]) == len(by_round[r]), f"{year} {r}: tree reached {len(ordered[r])} of {len(by_round[r])}"

    ids = {}
    for r in rounds:
        for i, m in enumerate(ordered[r], start=1):
            ids[id(m)] = f"{SHORT[r]}-{i}" if r != "Final" else "F"

    slots = []
    for r in rounds:
        for m in ordered[r]:
            srcs = []
            for team in (m.home, m.away):
                src = source_of(team, m)
                srcs.append(src if isinstance(src, str) else f'SlotSource.WinnerOf("{ids[id(src)]}")')
            slots.append((ids[id(m)], r, srcs))
    (third,) = by_round[THIRD]
    semis = ordered["Semi-finals"]
    third_srcs = []
    for team in (third.home, third.away):
        (sf,) = [s for s in semis if team in (s.home, s.away)]
        assert loser(sf) == team
        third_srcs.append(f'SlotSource.LoserOf("{ids[id(sf)]}")')
    slots.append(("3P", THIRD, third_srcs))
    return slots


def shape(slots):
    """The tree with group letters and positions blanked, for comparing editions."""
    return [(i, r, [s if "Group" not in s else "G" for s in srcs]) for i, r, srcs in slots]


def kotlin_brackets(all_slots: dict[int, list]) -> str:
    lines = [
        "package com.dreamxi.app.sim.worldcup",
        "",
        "// GENERATED by etl/export_wc_engine_data.py from the real 2006-2026",
        "// knockout results. Do not edit by hand: regenerate instead.",
        "//",
        "// Each tie's sides are traced from what really happened, so a slot",
        "// cannot be mistyped. WcTournamentReplayTest replays every edition with",
        "// nobody replaced and asserts every real pairing comes back.",
        "",
        "internal object WcBrackets {",
        "    val byYear: Map<Int, List<BracketSlot>> = mapOf(",
    ]
    for year, slots in all_slots.items():
        lines.append(f"        {year} to listOf(")
        for sid, rnd, (a, b) in slots:
            lines.append(f'            BracketSlot("{sid}", "{rnd}", {a}, {b}),')
        lines.append("        ),")
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def kotlin_thirds() -> str:
    rows = [line.rstrip("\n").split("\t") for line in THIRDS.read_text(encoding="utf-8").splitlines()]
    header, body = rows[0], rows[1:]
    slots = header[2:]
    assert slots == ["1A", "1B", "1D", "1E", "1G", "1I", "1K", "1L"], slots
    encoded = [f"{r[1]}={''.join(r[2:])}" for r in body]
    chunks = [" ".join(encoded[i:i + 6]) for i in range(0, len(encoded), 6)]
    out = [
        "package com.dreamxi.app.sim.worldcup",
        "",
        "// GENERATED by etl/export_wc_engine_data.py from FIFA's 2026 regulations,",
        "// Annex C, as published on Wikipedia (CC BY-SA 4.0). Do not edit by hand.",
        "//",
        "// One entry per possible set of eight qualifying third-placed groups:",
        "// \"BDEFIJKL=EJBDIFLK\" reads: when the thirds of B, D, E, F, I, J, K and L",
        "// qualify, group winner A meets 3E, B meets 3J, D meets 3B, E meets 3D,",
        "// G meets 3I, I meets 3F, K meets 3L and L meets 3K. Checked against the",
        "// published round-of-32 slots, all 495 combinations present.",
        "",
        "internal object WcThirdPlaceTable2026 {",
        "    /** The group winners that can be drawn against a third, in column order. */",
        '    val WINNER_GROUPS = listOf("A", "B", "D", "E", "G", "I", "K", "L")',
        "",
        '    private const val TABLE = """',
    ]
    out += chunks
    out += [
        '"""',
        "",
        "    /** qualifying groups (sorted, e.g. \"BDEFIJKL\") -> winner group -> third's group. */",
        "    val assignments: Map<String, Map<String, String>> by lazy {",
        "        TABLE.trim().split(Regex(\"\\\\s+\")).associate { entry ->",
        "            val (groups, thirds) = entry.split('=')",
        "            groups to WINNER_GROUPS.zip(thirds.map { it.toString() }).toMap()",
        "        }",
        "    }",
        "}",
        "",
    ]
    return "\n".join(out)


def export_fixtures(matches, events) -> None:
    TEST_OUT.mkdir(parents=True, exist_ok=True)
    with open(TEST_OUT / "entries.tsv", "w", encoding="utf-8", newline="") as ent, \
            open(TEST_OUT / "players.tsv", "w", encoding="utf-8", newline="") as ply:
        ent.write("year\tteam\tnation\tletter\tposition\tbottom\thost\tsquad_edition\n")
        grid_roles = list(wc_squads.GRID_COLUMNS)
        ply.write("year\tteam\tsofifa_id\tname\trating\tpositions\t"
                  + "\t".join(f"grid_{r.lower()}" for r in grid_roles) + "\tgrid_edition\n")
        for year in wc_fixtures.EDITIONS:
            letters = events["editions"][str(year)]["letters"]
            place = standings(matches, year, letters)
            size = {g: 0 for g in set(letters.values())}
            for team, (letter, pos) in place.items():
                size[letter] = max(size[letter], pos)
            for team, (letter, pos) in sorted(place.items(), key=lambda kv: kv[1]):
                pool = wc_squads.pool_for(year, team)
                squad = wc_squads.national_squad(pool.players, wc_squads.SQUAD_SIZE[year]) if pool.source_edition else ()
                ent.write(f"{year}\t{team}\t{wc_nations.canonical_name(team)}\t{letter}\t{pos}\t"
                          f"{int(pos == size[letter])}\t{int(team in wc_fixtures.HOSTS[year])}\t"
                          f"{pool.source_edition or ''}\n")
                for p in squad:
                    grid = p.grid_map or {}
                    ply.write(f"{year}\t{team}\t{p.sofifa_id}\t{p.name}\t{p.rating:.2f}\t{';'.join(p.positions)}\t"
                              + "\t".join("" if grid.get(r) is None else str(grid[r]) for r in grid_roles)
                              + f"\t{p.grid_edition or ''}\n")
    with open(TEST_OUT / "matches.tsv", "w", encoding="utf-8", newline="") as fh:
        fh.write("year\tround\tdate\thome\taway\thome_goals\taway_goals\thome_pens\taway_pens\tshootout_winner\n")
        for m in matches:
            fh.write(f"{m.edition}\t{m.round}\t{m.date}\t{m.home}\t{m.away}\t{m.home_goals}\t{m.away_goals}\t"
                     f"{'' if m.pens_home is None else m.pens_home}\t{'' if m.pens_away is None else m.pens_away}\t"
                     f"{m.shootout_winner or ''}\n")
    with open(TEST_OUT / "goals.tsv", "w", encoding="utf-8", newline="") as fh:
        fh.write("year\tdate\thome\taway\tteam\tscorer\tminute\tstoppage\tkind\n")
        for g in events["goals"]:
            fh.write(f"{g['edition']}\t{g['date']}\t{g['home']}\t{g['away']}\t{g['team']}\t{g['scorer']}\t"
                     f"{g['minute']}\t{g['stoppage'] or ''}\t{g['kind']}\n")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    matches = wc_fixtures.with_shootout_scores(wc_fixtures.load_all())
    events = json.loads(EVENTS.read_text(encoding="utf-8"))

    all_slots = {}
    for year in wc_fixtures.EDITIONS:
        slots = bracket(matches, year, events["editions"][str(year)]["letters"])
        all_slots[year] = slots
        print(f"{year}: {len(slots)} knockout ties")
        for sid, rnd, srcs in slots[: (16 if year == 2026 else 8)]:
            print(f"    {sid}: {srcs[0].replace('SlotSource.', '')} v {srcs[1].replace('SlotSource.', '')}")
    # 2006-2022 all used the same 32-team format, so their trees should agree
    # exactly — letters and positions included. A difference means a tie was
    # traced wrongly or a group position is off.
    for y in (2010, 2014, 2018, 2022):
        if all_slots[y] != all_slots[2006]:
            diff = [(a, b) for a, b in zip(all_slots[2006], all_slots[y]) if a != b]
            raise SystemExit(f"{y} bracket differs from 2006: {diff[:4]}")
    print("2006-2022 share one identical bracket")

    SIM_OUT.mkdir(parents=True, exist_ok=True)
    (SIM_OUT / "WcBrackets.kt").write_text(kotlin_brackets(all_slots), encoding="utf-8")
    (SIM_OUT / "WcThirdPlaceTable2026.kt").write_text(kotlin_thirds(), encoding="utf-8")
    export_fixtures(matches, events)
    print(f"wrote WcBrackets.kt, WcThirdPlaceTable2026.kt and {TEST_OUT}")


if __name__ == "__main__":
    main()
