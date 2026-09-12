"""
Merges the per-table parsed rows (parse_raw.py) into one record per
player-season, keyed on (player name, team) — FBref's own "Rk" numbering
differs per stat table, so identity has to be re-established by name+team,
not by rank. A player who was transferred mid-season appears as two separate
rows (one per club) in FBref's own data; we keep them separate here too,
matching a real player_season_stats row per club_season.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from parse_raw import (
    PlayerRow,
    parse_defense_compact,
    parse_fixtures,
    parse_keepers_compact,
    parse_league_table,
    parse_player_table,
    parse_shooting_compact,
    parse_standard_compact,
)
from roles import TM_CLUB_ID_TO_FBREF_NAME_PL, load_roles_by_club, resolve_role_group


def _to_int(s: str) -> int:
    return int(s.replace(",", "")) if s not in ("", None) else 0


def _to_float(s: str) -> float:
    return float(s) if s not in ("", None) else 0.0


@dataclass
class PlayerSeason:
    player: str
    team: str
    nationality: str
    position: str  # FBref primary code, e.g. "DF", "MF,FW" (first listed = primary)
    role_group: str  # one of GK/CB/FB/DM/CM/CAM/Winger/ST — see roles.py
    age: int
    birth_year: int  # NOT just cosmetic — this is part of a player's identity key
    # (see supabase_load.py) to avoid conflating two different real people who
    # happen to share a common short name, e.g. two different "Rodri"s.
    appearances: int = 0
    minutes: int = 0
    goals: int = 0
    assists: int = 0
    yellow_cards: int = 0
    red_cards: int = 0
    shots: int = 0
    shots_on_target: int = 0
    tackles_won: int = 0
    interceptions: int = 0
    # goalkeeper-only
    gk_saves: int | None = None
    gk_save_pct: float | None = None
    gk_clean_sheets: int | None = None
    gk_goals_against: int | None = None

    @property
    def primary_position(self) -> str:
        return self.position.split(",")[0]


def _standard_fields(row: PlayerRow) -> dict:
    # stats order (see raw/standard_stats_*.txt header):
    # MP Starts Min 90s Gls Ast G+A G-PK PK PKatt CrdY CrdR Gls90 Ast90 G+A90 G-PK90 G+A-PK90
    s = row.stats
    return dict(
        appearances=_to_int(s[0]),
        minutes=_to_int(s[2]),
        goals=_to_int(s[4]),
        assists=_to_int(s[5]),
        yellow_cards=_to_int(s[10]),
        red_cards=_to_int(s[11]),
    )


def _shooting_fields(row: PlayerRow) -> dict:
    # stats order: 90s Gls Sh SoT SoT% Sh/90 SoT/90 G/Sh G/SoT PK PKatt
    s = row.stats
    return dict(shots=_to_int(s[2]), shots_on_target=_to_int(s[3]))


def _defense_fields(row: PlayerRow) -> dict:
    """
    stats order: 90s TklW Int

    CORRECTED 2026-08-29. This previously read `tackles_won=s[2]` against a
    header that claimed the columns were "90s Tkl TklW". They were actually
    "90s TklW Int" — FBref's Tkl column is blank post-purge and was never
    captured — so s[2] is INTERCEPTIONS, and every 2023/24 player has been
    loaded with their interception count stored as tackles_won since the
    pilot. Verified against the live FBref table on three players
    (Van Dijk 23/35, Haaland 3/2, Saliba 26/29), all of which match
    s[1]=tackles_won, s[2]=interceptions.
    """
    s = row.stats
    return dict(
        tackles_won=_to_int(s[1]),
        interceptions=_to_int(s[2]) if len(s) > 2 else 0,
    )


def _keeper_fields(row: PlayerRow) -> dict:
    # stats order: MP Starts Min 90s GA GA90 SoTA Saves Save% W D L CS CS% PKatt PKA PKsv PKm Save%
    s = row.stats
    return dict(
        gk_goals_against=_to_int(s[4]),
        gk_saves=_to_int(s[7]),
        gk_save_pct=_to_float(s[8]),
        gk_clean_sheets=_to_int(s[12]),
    )


def build_player_seasons(
    standard_files: tuple[str, ...] = ("standard_stats_chunk1.txt", "standard_stats_chunk2.txt"),
    shooting_files: tuple[str, ...] = ("shooting_chunk1.txt", "shooting_chunk2.txt"),
    defense_file: str = "defense_2023-2024_PL.txt",
    keepers_file: str = "keepers_2023-2024_PL.txt",
    roles_file: str = "transfermarkt_roles_2023-2024_PL.txt",
    roles_club_id_map: dict[str, str] = TM_CLUB_ID_TO_FBREF_NAME_PL,
) -> list[PlayerSeason]:
    standard = parse_player_table(*standard_files)
    shooting = parse_player_table(*shooting_files)
    defense = parse_player_table(defense_file)
    keepers = parse_player_table(keepers_file)

    def index_by_identity(rows: list[PlayerRow]) -> dict[tuple[str, str], PlayerRow]:
        return {(r.player, r.team): r for r in rows}

    shooting_idx = index_by_identity(shooting)
    defense_idx = index_by_identity(defense)
    keeper_idx = index_by_identity(keepers)
    roles_by_club = load_roles_by_club(roles_file, roles_club_id_map)

    seasons: list[PlayerSeason] = []
    for row in standard:
        identity = (row.player, row.team)
        ps = PlayerSeason(
            player=row.player,
            team=row.team,
            nationality=row.nationality,
            position=row.position,
            role_group=resolve_role_group(row.player, row.team, row.position, roles_by_club),
            age=row.age,
            birth_year=row.birth_year,
            **_standard_fields(row),
        )
        if identity in shooting_idx:
            for k, v in _shooting_fields(shooting_idx[identity]).items():
                setattr(ps, k, v)
        if identity in defense_idx:
            for k, v in _defense_fields(defense_idx[identity]).items():
                setattr(ps, k, v)
        if identity in keeper_idx:
            for k, v in _keeper_fields(keeper_idx[identity]).items():
                setattr(ps, k, v)
        seasons.append(ps)
    return seasons


def build_player_seasons_compact(
    standard_file: str,
    shooting_file: str,
    defense_file: str,
    keepers_file: str,
    roles_file: str,
    roles_club_id_map: dict[str, str],
) -> list[PlayerSeason]:
    """
    Same purpose as build_player_seasons(), but reads the newer compact
    pipe-delimited export format (parse_*_compact in parse_raw.py) used for
    every season pulled after the original PL/La Liga 2023/24 pilot files.
    """
    standard = parse_standard_compact(standard_file)
    shooting = {(r["player"], r["team"]): r for r in parse_shooting_compact(shooting_file)}
    defense = {(r["player"], r["team"]): r for r in parse_defense_compact(defense_file)}
    keepers = {(r["player"], r["team"]): r for r in parse_keepers_compact(keepers_file)}
    roles_by_club = load_roles_by_club(roles_file, roles_club_id_map)

    seasons: list[PlayerSeason] = []
    for row in standard:
        identity = (row["player"], row["team"])
        ps = PlayerSeason(
            player=row["player"],
            team=row["team"],
            nationality=row["nationality"],
            position=row["position"],
            role_group=resolve_role_group(row["player"], row["team"], row["position"], roles_by_club),
            age=row["age"],
            birth_year=row["birth_year"],
            appearances=row["appearances"],
            minutes=row["minutes"],
            goals=row["goals"],
            assists=row["assists"],
            yellow_cards=row["yellow_cards"],
            red_cards=row["red_cards"],
        )
        if identity in shooting:
            ps.shots = shooting[identity]["shots"]
            ps.shots_on_target = shooting[identity]["shots_on_target"]
        if identity in defense:
            ps.tackles_won = defense[identity]["tackles_won"]
            ps.interceptions = defense[identity].get("interceptions", 0)
        if identity in keepers:
            k = keepers[identity]
            ps.gk_saves = k["gk_saves"]
            ps.gk_save_pct = k["gk_save_pct"]
            ps.gk_clean_sheets = k["gk_clean_sheets"]
            ps.gk_goals_against = k["gk_goals_against"]
        seasons.append(ps)
    return seasons


if __name__ == "__main__":
    seasons = build_player_seasons()
    print(f"{len(seasons)} player-season records built")
    gks = [s for s in seasons if s.primary_position == "GK"]
    print(f"  of which {len(gks)} are goalkeepers with GK stats attached")
    missing_gk_stats = [s for s in gks if s.gk_saves is None]
    print(f"  goalkeepers missing GK stats (name/team mismatch to check): {len(missing_gk_stats)}")
    for s in missing_gk_stats[:5]:
        print("   ", s.player, "|", s.team)
    sample = next(s for s in seasons if s.player == "Erling Haaland")
    print("sample (Haaland):", sample)
