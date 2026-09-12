"""
Parses the raw FBref text dumps in etl/raw/ (extracted via a real browser
session — see PROJECT_SPEC_v2.md §6.1 for why headless scraping doesn't work
against FBref's Cloudflare bot-challenge) into structured Python records.

These files are plain space-separated text copied from FBref's rendered
tables, not clean CSV, so parsing leans on a few structural facts about the
data rather than naive whitespace-splitting:
  - A player row's "Nation" field is always exactly two tokens: a lowercase
    2-3 letter code followed by an uppercase 3-letter code (e.g. "eng ENG").
    That's a reliable anchor for splitting "rank + player name" from the rest.
  - "Age" is a bare 1-2 digit token immediately followed by a 4-digit
    "Born" year token — the only place that exact pattern occurs — which
    anchors the end of the (variable-length, multi-word) team name.
  - Fixture rows are matched against the known 20 club names directly
    (see CLUB_NAMES) rather than parsed positionally, since attendance/venue/
    referee fields are themselves variable-length and not needed by our schema.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

RAW_DIR = Path(__file__).parent / "raw"

NATION_RE = re.compile(r"\b([a-z]{2,3}) ([A-Z]{3})\b")
AGE_BORN_RE = re.compile(r"\b(\d{1,2}) ((?:19|20)\d{2})\b")
SCORE_RE = re.compile(r"(\d+)–(\d+)")  # en dash only — a plain hyphen also appears in dates


@dataclass
class PlayerRow:
    rank: int
    player: str
    nationality: str
    position: str
    team: str
    age: int
    birth_year: int
    stats: list[str] = field(default_factory=list)  # remaining raw tokens, before "Matches"


def _split_player_row(line: str) -> PlayerRow | None:
    line = line.strip()
    if not line or line.startswith("Rk ") or "Matches" not in line:
        return None

    nation_match = NATION_RE.search(line)
    if not nation_match:
        return None

    before_nation = line[: nation_match.start()].strip()
    after_nation = line[nation_match.end() :].strip()

    rank_str, _, player = before_nation.partition(" ")
    if not rank_str.isdigit():
        return None

    # after_nation starts with: POS TEAM... AGE BORN STAT1 STAT2 ... Matches
    pos, _, rest = after_nation.partition(" ")
    age_born = AGE_BORN_RE.search(rest)
    if not age_born:
        return None

    team = rest[: age_born.start()].strip()
    tail = rest[age_born.end() :].strip()
    stats = tail.split(" ")
    if stats and stats[-1] == "Matches":
        stats = stats[:-1]

    return PlayerRow(
        rank=int(rank_str),
        player=player.strip(),
        nationality=nation_match.group(2),
        position=pos,
        team=team,
        age=int(age_born.group(1)),
        birth_year=int(age_born.group(2)),
        stats=stats,
    )


def parse_player_table(*filenames: str) -> list[PlayerRow]:
    """
    Parses one or more chunk files of the same table and concatenates rows.
    Chunk files can overlap at their boundary (see the standard-stats
    extraction notes) — dedupe by rank, keeping the first occurrence.
    """
    rows: list[PlayerRow] = []
    seen_ranks: set[int] = set()
    for filename in filenames:
        text = (RAW_DIR / filename).read_text(encoding="utf-8")
        for line in text.splitlines():
            row = _split_player_row(line)
            if row and row.rank not in seen_ranks:
                seen_ranks.add(row.rank)
                rows.append(row)
    return rows


@dataclass
class LeagueTableRow:
    position: int
    club: str
    played: int
    wins: int
    draws: int
    losses: int
    goals_for: int
    goals_against: int
    points: int


def parse_league_table(filename: str) -> list[LeagueTableRow]:
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows: list[LeagueTableRow] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("Rk "):
            continue
        parts = line.split(" ")
        rank = int(parts[0])
        pts = int(parts[-1])
        gd_str = parts[-2]  # noqa: F841 (unused, kept for clarity — GD is derivable)
        ga = int(parts[-3])
        gf = int(parts[-4])
        losses = int(parts[-5])
        draws = int(parts[-6])
        wins = int(parts[-7])
        played = int(parts[-8])
        club = " ".join(parts[1:-8])
        rows.append(LeagueTableRow(rank, club, played, wins, draws, losses, gf, ga, pts))
    return rows


# PL club names, kept as the default for backward compatibility with the
# pilot season. Pass a different `club_names` list to parse_fixtures() for
# any other league (see build_dataset.py, which derives it from that
# league's own parsed league table instead of hardcoding it).
PL_CLUB_NAMES = [
    "Manchester City", "Arsenal", "Liverpool", "Aston Villa", "Tottenham",
    "Chelsea", "Newcastle", "Manchester Utd", "West Ham", "Crystal Palace",
    "Brighton", "Bournemouth", "Fulham", "Wolves", "Everton", "Brentford",
    "Nottingham", "Luton Town", "Burnley", "Sheffield United",
]


@dataclass
class FixtureRow:
    matchday: str
    home: str
    away: str
    home_goals: int
    away_goals: int


def parse_fixtures(filename: str, club_names: list[str] = PL_CLUB_NAMES) -> list[FixtureRow]:
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows: list[FixtureRow] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("Wk "):
            continue
        score = SCORE_RE.search(line)
        if not score:
            continue
        occurrences = []
        for club in club_names:
            idx = line.find(club)
            if idx != -1:
                occurrences.append((idx, club))
        occurrences.sort(key=lambda pair: pair[0])
        if len(occurrences) < 2:
            continue
        home, away = occurrences[0][1], occurrences[1][1]
        matchday = line.split(" ", 1)[0]
        rows.append(FixtureRow(matchday, home, away, int(score.group(1)), int(score.group(2))))
    return rows


def parse_fixtures_compact(filename: str) -> list[FixtureRow]:
    """
    Parses the newer, cleaner "wk|home|score|away" extraction format (one
    line per fixture, pipe-delimited, no attendance/venue/referee noise) —
    used for every season pulled after the original PL/La Liga 2023/24 pilot
    files, which used parse_fixtures()'s heavier positional-matching approach.
    """
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows: list[FixtureRow] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        wk, home, score, away = line.split("|")
        score_match = SCORE_RE.search(score)
        if not score_match:
            continue
        rows.append(FixtureRow(wk, home, away, int(score_match.group(1)), int(score_match.group(2))))
    return rows


def parse_standard_compact(filename: str) -> list[dict]:
    """Compact format: player|nation|pos|team|age|born|apps|starts|minutes|goals|assists|yellow|red"""
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("|")
        player, nation, pos, team, age, born, apps, starts, minutes, goals, assists, yellow, red = parts
        rows.append(dict(
            player=player, nationality=nation, position=pos, team=team,
            age=int(age), birth_year=int(born),
            appearances=int(apps), minutes=int(minutes.replace(",", "")),
            goals=int(goals), assists=int(assists),
            yellow_cards=int(yellow), red_cards=int(red),
        ))
    return rows


def parse_shooting_compact(filename: str) -> list[dict]:
    """Compact format: player|team|shots|sot"""
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        player, team, shots, sot = line.split("|")
        rows.append(dict(player=player, team=team, shots=int(shots), shots_on_target=int(sot)))
    return rows


def parse_defense_compact(filename: str) -> list[dict]:
    """
    Compact format: player|team|tackles_won[|interceptions]

    CORRECTED 2026-08-29. The previous docstring here claimed FBref's
    interceptions column "went blank site-wide post-Jan-2026" and the
    extraction dropped it accordingly, storing 0 for every player. That was
    wrong — verified against the live FBref defense table (e.g. Van Dijk
    2023/24: tackles_won=23, interceptions=35, both populated). The Jan-2026
    purge took blocks/clearances/challenges/tackle-zones, but tackles_won
    and interceptions both survived. Dropping interceptions meant defenders
    were rated on roughly HALF their available defensive signal, which is a
    large part of why elite centre-backs scored mid-pack (see
    ATTRIBUTE_FORMULA_SPEC.md §4.5).

    Three-field files (older extractions) are still accepted and report
    interceptions=0, so any season not yet re-scraped keeps parsing.
    """
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("|")
        if len(parts) == 4:
            player, team, tklw, inter = parts
        else:
            player, team, tklw = parts
            inter = "0"
        rows.append(dict(
            player=player,
            team=team,
            tackles_won=int(tklw or 0),
            interceptions=int(inter or 0),
        ))
    return rows


def parse_keepers_compact(filename: str) -> list[dict]:
    """Compact format: player|team|saves|save_pct|clean_sheets|goals_against"""
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        player, team, saves, save_pct, cs, ga = line.split("|")
        rows.append(dict(
            player=player, team=team, gk_saves=int(saves),
            gk_save_pct=float(save_pct) if save_pct else 0.0,
            gk_clean_sheets=int(cs), gk_goals_against=int(ga),
        ))
    return rows


if __name__ == "__main__":
    league = parse_league_table("league_table_2023-2024_PL.txt")
    fixtures = parse_fixtures("fixtures_2023-2024_PL.txt")
    standard = parse_player_table("standard_stats_chunk1.txt", "standard_stats_chunk2.txt")
    keepers = parse_player_table("keepers_2023-2024_PL.txt")

    print(f"league table: {len(league)} clubs")
    print(f"fixtures: {len(fixtures)} matches")
    print(f"standard stats: {len(standard)} player-season rows")
    print(f"keepers: {len(keepers)} player-season rows")

    print("\nsample league row:", league[-1])
    print("sample fixture:", fixtures[0])
    print("sample standard row:", standard[0])
    print("sample keeper row:", keepers[0])
