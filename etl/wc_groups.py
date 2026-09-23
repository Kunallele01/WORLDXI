"""
Group tables for every shipped World Cup, and the bottom team of each group.

WHY THIS EXISTS. The agreed design is that the user's XI takes over a nation
that finished BOTTOM of its group, drawn from the union of those nations —
the same shape as the club mode's relegation-zone draw. So the takeover pool
has to be computed, not typed in.

WHY THE GROUPS HAVE TO BE REBUILT. Neither source labels them: every match is
just tagged "Group stage". They recover exactly anyway, because a World Cup
group is a clique — its four nations play each other and nobody else in that
round — so the connected components of the group-stage opponent graph ARE the
groups. This is asserted (every component must hold exactly four nations) so a
format change shows up as a failure rather than a silently wrong table.

TIEBREAKERS follow the real rules: points, then goal difference, then goals
scored, then head-to-head among the teams still level. The club mode's table
sorts on GD then GF and has no head-to-head step, which is why this is here
and not reused from there.
"""
from __future__ import annotations

import sys
from collections import defaultdict
from dataclasses import dataclass, field

import wc_fixtures

GROUP_ROUND = "Group stage"
GROUP_SIZE = 4


@dataclass
class Standing:
    team: str
    played: int = 0
    won: int = 0
    drawn: int = 0
    lost: int = 0
    goals_for: int = 0
    goals_against: int = 0
    beaten: set[str] = field(default_factory=set)

    @property
    def points(self) -> int:
        return self.won * 3 + self.drawn

    @property
    def goal_difference(self) -> int:
        return self.goals_for - self.goals_against


def groups_of(matches: list[wc_fixtures.Match], edition: int) -> list[list[str]]:
    rows = [
        m for m in matches
        if m.edition == edition and m.round == GROUP_ROUND and m.played
    ]
    adjacent: dict[str, set[str]] = defaultdict(set)
    for m in rows:
        adjacent[m.home].add(m.away)
        adjacent[m.away].add(m.home)

    seen: set[str] = set()
    groups: list[list[str]] = []
    for team in adjacent:
        if team in seen:
            continue
        stack, component = [team], set()
        while stack:
            node = stack.pop()
            if node in component:
                continue
            component.add(node)
            stack.extend(adjacent[node] - component)
        seen |= component
        groups.append(sorted(component))

    bad = [g for g in groups if len(g) != GROUP_SIZE]
    if bad:
        raise SystemExit(
            f"{edition}: group stage did not split into fours -> {bad}. "
            "The format changed, or a match is missing."
        )
    return sorted(groups)


def table(matches: list[wc_fixtures.Match], edition: int, group: list[str]) -> list[Standing]:
    members = set(group)
    standings = {t: Standing(t) for t in group}
    for m in matches:
        if m.edition != edition or m.round != GROUP_ROUND or not m.played:
            continue
        if m.home not in members or m.away not in members:
            continue
        home, away = standings[m.home], standings[m.away]
        home.played += 1
        away.played += 1
        home.goals_for += m.home_goals
        home.goals_against += m.away_goals
        away.goals_for += m.away_goals
        away.goals_against += m.home_goals
        if m.home_goals > m.away_goals:
            home.won += 1
            away.lost += 1
            home.beaten.add(m.away)
        elif m.away_goals > m.home_goals:
            away.won += 1
            home.lost += 1
            away.beaten.add(m.home)
        else:
            home.drawn += 1
            away.drawn += 1

    ordered = sorted(
        standings.values(),
        key=lambda s: (s.points, s.goal_difference, s.goals_for),
        reverse=True,
    )
    return _break_ties(ordered)


def _break_ties(ordered: list[Standing]) -> list[Standing]:
    """Reorder runs that are level on points, GD and GF by head-to-head."""
    out: list[Standing] = []
    run: list[Standing] = []
    key = lambda s: (s.points, s.goal_difference, s.goals_for)  # noqa: E731
    for standing in ordered:
        if run and key(standing) != key(run[0]):
            out.extend(_by_head_to_head(run))
            run = []
        run.append(standing)
    out.extend(_by_head_to_head(run))
    return out


def _by_head_to_head(run: list[Standing]) -> list[Standing]:
    if len(run) < 2:
        return run
    names = {s.team for s in run}
    return sorted(run, key=lambda s: len(s.beaten & names), reverse=True)


def bottom_teams(matches: list[wc_fixtures.Match], edition: int) -> list[str]:
    """The nation that finished last in each group — the takeover pool."""
    return [table(matches, edition, g)[-1].team for g in groups_of(matches, edition)]


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    matches = wc_fixtures.load_all()

    pool: dict[int, list[str]] = {}
    for edition in wc_fixtures.EDITIONS:
        groups = groups_of(matches, edition)
        bottoms = bottom_teams(matches, edition)
        pool[edition] = bottoms
        print(f"\n=== World Cup {edition}: {len(groups)} groups ===")
        for group in groups:
            rows = table(matches, edition, group)
            line = "  " + " | ".join(
                f"{s.team} {s.points}pts {s.goal_difference:+d}" for s in rows
            )
            print(line + f"   -> bottom: {rows[-1].team}")

    print("\n" + "=" * 66)
    print("TAKEOVER POOL (every nation that finished bottom of a group)")
    everyone = sorted({t for teams in pool.values() for t in teams})
    for edition in wc_fixtures.EDITIONS:
        print(f"  {edition}: {', '.join(pool[edition])}")
    print(f"\n  {len(everyone)} distinct nations across "
          f"{sum(len(v) for v in pool.values())} slots")


if __name__ == "__main__":
    main()
