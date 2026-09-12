"""
Loads a league/season pilot dataset into Supabase via the REST API, using
the service_role key (bypasses RLS — §5.2, reference tables are ETL-write-
only). Not idempotent: re-running the same league+season will insert
duplicate rows since the reference tables have no unique constraints beyond
id. Truncate first if you need to re-run against a clean slate.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import requests
from dotenv import load_dotenv

from attributes import compute_all_attributes
from fifa_lookup import FifaLookup
from build_dataset import build_player_seasons, build_player_seasons_compact
from parse_raw import PL_CLUB_NAMES, parse_fixtures, parse_fixtures_compact, parse_league_table
from roles import (
    TM_CLUB_ID_TO_FBREF_NAME_LALIGA,
    TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2019_20,
    TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2020_21,
    TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2021_22,
    TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2022_23,
    TM_CLUB_ID_TO_FBREF_NAME_PL,
    TM_CLUB_ID_TO_FBREF_NAME_PL_2019_20,
    TM_CLUB_ID_TO_FBREF_NAME_PL_2020_21,
    TM_CLUB_ID_TO_FBREF_NAME_PL_2021_22,
    TM_CLUB_ID_TO_FBREF_NAME_PL_2022_23,
)

load_dotenv(Path(__file__).parent / ".env")

# FIFA quality anchor, loaded once (see attributes.py "bias fix, part 3")
FIFA = FifaLookup()

SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]

HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
    "Prefer": "return=representation",
}
BATCH = 200


def rest(method: str, path: str, **kwargs):
    resp = requests.request(method, f"{SUPABASE_URL}/rest/v1/{path}", headers=HEADERS, **kwargs)
    if not resp.ok:
        raise RuntimeError(f"{method} {path} -> {resp.status_code}: {resp.text}")
    return resp.json() if resp.content else None


@dataclass
class LeagueConfig:
    league_name: str
    country: str
    season_label: str
    start_year: int
    league_table_file: str
    fixtures_file: str
    standard_files: tuple[str, ...]
    shooting_files: tuple[str, ...]
    defense_file: str
    keepers_file: str
    roles_file: str
    roles_club_id_map: dict[str, str]
    fixture_club_names: list[str] = field(default_factory=list)
    use_compact: bool = False  # True for every season pulled after the original pilot (see parse_raw.py)


PREMIER_LEAGUE_2023_24 = LeagueConfig(
    league_name="Premier League",
    country="England",
    season_label="2023/24",
    start_year=2023,
    league_table_file="league_table_2023-2024_PL.txt",
    fixtures_file="fixtures_2023-2024_PL.txt",
    standard_files=("standard_stats_chunk1.txt", "standard_stats_chunk2.txt"),
    shooting_files=("shooting_chunk1.txt", "shooting_chunk2.txt"),
    defense_file="defense_2023-2024_PL.txt",
    keepers_file="keepers_2023-2024_PL.txt",
    roles_file="transfermarkt_roles_2023-2024_PL.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_PL,
    fixture_club_names=PL_CLUB_NAMES,
)

LA_LIGA_2023_24 = LeagueConfig(
    league_name="La Liga",
    country="Spain",
    season_label="2023/24",
    start_year=2023,
    league_table_file="league_table_2023-2024_LaLiga.txt",
    fixtures_file="fixtures_2023-2024_LaLiga.txt",
    standard_files=("standard_stats_LaLiga_chunk1.txt", "standard_stats_LaLiga_chunk2.txt"),
    shooting_files=("shooting_LaLiga_chunk1.txt", "shooting_LaLiga_chunk2.txt"),
    defense_file="defense_LaLiga_2023-2024.txt",
    keepers_file="keepers_LaLiga_2023-2024.txt",
    roles_file="transfermarkt_roles_2023-2024_LaLiga.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_LALIGA,
    fixture_club_names=[],  # filled in from the parsed league table, see load()
)


def load(config: LeagueConfig):
    print(f"Parsing raw data for {config.league_name} {config.season_label}...")
    league_table = parse_league_table(config.league_table_file)
    if config.use_compact:
        fixtures = parse_fixtures_compact(config.fixtures_file)
        seasons_data = build_player_seasons_compact(
            standard_file=config.standard_files[0],
            shooting_file=config.shooting_files[0],
            defense_file=config.defense_file,
            keepers_file=config.keepers_file,
            roles_file=config.roles_file,
            roles_club_id_map=config.roles_club_id_map,
        )
    else:
        club_names = config.fixture_club_names or [row.club for row in league_table]
        fixtures = parse_fixtures(config.fixtures_file, club_names=club_names)
        seasons_data = build_player_seasons(
            standard_files=config.standard_files,
            shooting_files=config.shooting_files,
            defense_file=config.defense_file,
            keepers_file=config.keepers_file,
            roles_file=config.roles_file,
            roles_club_id_map=config.roles_club_id_map,
        )
    team_ga_per_game = {row.club: row.goals_against / row.played for row in league_table if row.played}
    attrs = compute_all_attributes(seasons_data, team_ga_per_game=team_ga_per_game,
                                   fifa_lookup=FIFA, season_label=config.season_label)

    print("1/6 Finding or inserting league...")
    existing = rest("GET", "leagues", params={"name": f"eq.{config.league_name}", "select": "id"})
    if existing:
        league_id = existing[0]["id"]
        print(f"   Reusing existing league row (id={league_id}).")
    else:
        league = rest("POST", "leagues", json={
            "name": config.league_name, "country": config.country, "default_tier": "B",
        })[0]
        league_id = league["id"]

    print("2/6 Inserting season...")
    season = rest("POST", "seasons", json={
        "league_id": league_id, "label": config.season_label,
        "start_year": config.start_year, "data_tier": "B",
    })[0]
    season_id = season["id"]

    print("3/6 Inserting club_seasons...")
    club_season_id_by_name: dict[str, int] = {}
    club_season_rows = [
        {
            "season_id": season_id,
            "club_name": row.club,
            "final_position": row.position,
            "wins": row.wins,
            "draws": row.draws,
            "losses": row.losses,
            "goals_for": row.goals_for,
            "goals_against": row.goals_against,
            "points": row.points,
        }
        for row in league_table
    ]
    inserted_clubs = rest("POST", "club_seasons", json=club_season_rows)
    for row in inserted_clubs:
        club_season_id_by_name[row["club_name"]] = row["id"]

    bottom3 = sorted(league_table, key=lambda r: -r.position)[:3]
    print(f"   {len(inserted_clubs)} clubs inserted. Real relegation zone (18th-20th, "
          f"any of which may be replaced at simulate time): {[c.club for c in bottom3]}")

    print("4/6 Inserting fixtures...")
    fixture_rows = [
        {
            "season_id": season_id,
            "matchday": int(f.matchday) if f.matchday.isdigit() else None,
            "home_club_season_id": club_season_id_by_name[f.home],
            "away_club_season_id": club_season_id_by_name[f.away],
            "home_goals": f.home_goals,
            "away_goals": f.away_goals,
        }
        for f in fixtures
    ]
    inserted_fixtures = 0
    for i in range(0, len(fixture_rows), BATCH):
        batch = rest("POST", "fixtures", json=fixture_rows[i : i + BATCH])
        inserted_fixtures += len(batch)
    print(f"   {inserted_fixtures} fixtures inserted.")

    print("5/6 Finding or inserting players + inserting player_season_stats...")
    # Identity key is (full_name, birth_year), NOT full_name alone — two
    # different real people can share a common short name (e.g. two
    # different "Rodri"s, one born 1996 at Man City, one born 2000 at Real
    # Betis, discovered when this exact bug happened on first load). Birth
    # year is the cheapest signal available to tell them apart; day/month
    # aren't in the data, so date_of_birth is stored as Jan 1 of that year
    # (documented year-only precision, not a real birth date).
    unique_identities = sorted({(s.player, s.birth_year) for s in seasons_data})
    player_id_by_identity: dict[tuple[str, int], int] = {}

    names_in_batch = sorted({name for name, _ in unique_identities})
    for i in range(0, len(names_in_batch), BATCH):
        batch_names = names_in_batch[i : i + BATCH]
        in_clause = ",".join(f'"{n}"' for n in batch_names)
        existing_players = rest("GET", "players", params={
            "full_name": f"in.({in_clause})", "select": "id,full_name,date_of_birth",
        })
        for row in existing_players:
            birth_year = int(row["date_of_birth"][:4]) if row["date_of_birth"] else None
            if birth_year is not None:
                player_id_by_identity[(row["full_name"], birth_year)] = row["id"]

    new_identities = [ident for ident in unique_identities if ident not in player_id_by_identity]
    for i in range(0, len(new_identities), BATCH):
        batch_idents = new_identities[i : i + BATCH]
        batch = rest("POST", "players", json=[
            {"full_name": name, "date_of_birth": f"{birth_year}-01-01"}
            for name, birth_year in batch_idents
        ])
        for row, ident in zip(batch, batch_idents):
            player_id_by_identity[ident] = row["id"]
    print(f"   {len(unique_identities)} unique player identities this season "
          f"({len(new_identities)} newly inserted, "
          f"{len(unique_identities) - len(new_identities)} already existed).")

    stat_rows = []
    for s in seasons_data:
        a = attrs[(s.player, s.team)]
        stat_rows.append({
            "player_id": player_id_by_identity[(s.player, s.birth_year)],
            "club_season_id": club_season_id_by_name[s.team],
            "primary_position": s.role_group,
            "secondary_positions": s.position.split(","),
            "appearances": s.appearances,
            "minutes": s.minutes,
            "goals": s.goals,
            "assists": s.assists,
            "yellow_cards": s.yellow_cards,
            "red_cards": s.red_cards,
            "shots": s.shots,
            "shots_on_target": s.shots_on_target,
            "tackles": s.tackles_won,
            "interceptions": s.interceptions,
            "saves": s.gk_saves,
            "save_pct": s.gk_save_pct,
            "clean_sheets": s.gk_clean_sheets,
            "finishing": a.finishing,
            "creation": a.creation,
            "carrying": a.carrying,
            "buildup": a.buildup,
            "defense": a.defense,
            "physical": a.physical,
            "overall_rating": a.overall_rating,
            "traits": a.traits,
        })
    inserted_stats = 0
    for i in range(0, len(stat_rows), BATCH):
        batch = rest("POST", "player_season_stats", json=stat_rows[i : i + BATCH])
        inserted_stats += len(batch)
    print(f"6/6 {inserted_stats} player_season_stats rows inserted.")

    print(f"\nDone. {config.league_name} {config.season_label} loaded: "
          f"{len(inserted_clubs)} clubs, {inserted_fixtures} fixtures, "
          f"{len(unique_identities)} players, {inserted_stats} player-season stat rows.")


PREMIER_LEAGUE_2022_23 = LeagueConfig(
    league_name="Premier League",
    country="England",
    season_label="2022/23",
    start_year=2022,
    league_table_file="league_table_2022-2023_PL.txt",
    fixtures_file="fixtures_2022-2023_PL.txt",
    standard_files=("standard_2022-2023_PL.txt",),
    shooting_files=("shooting_2022-2023_PL.txt",),
    defense_file="defense_2022-2023_PL.txt",
    keepers_file="keepers_2022-2023_PL.txt",
    roles_file="transfermarkt_roles_2022-2023_PL.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_PL_2022_23,
    use_compact=True,
)


PREMIER_LEAGUE_2021_22 = LeagueConfig(
    league_name="Premier League",
    country="England",
    season_label="2021/22",
    start_year=2021,
    league_table_file="league_table_2021-2022_PL.txt",
    fixtures_file="fixtures_2021-2022_PL.txt",
    standard_files=("standard_2021-2022_PL.txt",),
    shooting_files=("shooting_2021-2022_PL.txt",),
    defense_file="defense_2021-2022_PL.txt",
    keepers_file="keepers_2021-2022_PL.txt",
    roles_file="transfermarkt_roles_2021-2022_PL.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_PL_2021_22,
    use_compact=True,
)


PREMIER_LEAGUE_2020_21 = LeagueConfig(
    league_name="Premier League",
    country="England",
    season_label="2020/21",
    start_year=2020,
    league_table_file="league_table_2020-2021_PL.txt",
    fixtures_file="fixtures_2020-2021_PL.txt",
    standard_files=("standard_2020-2021_PL.txt",),
    shooting_files=("shooting_2020-2021_PL.txt",),
    defense_file="defense_2020-2021_PL.txt",
    keepers_file="keepers_2020-2021_PL.txt",
    roles_file="transfermarkt_roles_2020-2021_PL.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_PL_2020_21,
    use_compact=True,
)


PREMIER_LEAGUE_2019_20 = LeagueConfig(
    league_name="Premier League",
    country="England",
    season_label="2019/20",
    start_year=2019,
    league_table_file="league_table_2019-2020_PL.txt",
    fixtures_file="fixtures_2019-2020_PL.txt",
    standard_files=("standard_2019-2020_PL.txt",),
    shooting_files=("shooting_2019-2020_PL.txt",),
    defense_file="defense_2019-2020_PL.txt",
    keepers_file="keepers_2019-2020_PL.txt",
    roles_file="transfermarkt_roles_2019-2020_PL.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_PL_2019_20,
    use_compact=True,
)


LA_LIGA_2022_23 = LeagueConfig(
    league_name="La Liga",
    country="Spain",
    season_label="2022/23",
    start_year=2022,
    league_table_file="league_table_2022-2023_LaLiga.txt",
    fixtures_file="fixtures_2022-2023_LaLiga.txt",
    standard_files=("standard_2022-2023_LaLiga.txt",),
    shooting_files=("shooting_2022-2023_LaLiga.txt",),
    defense_file="defense_2022-2023_LaLiga.txt",
    keepers_file="keepers_2022-2023_LaLiga.txt",
    roles_file="transfermarkt_roles_2022-2023_LaLiga.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2022_23,
    use_compact=True,
)


LA_LIGA_2021_22 = LeagueConfig(
    league_name="La Liga",
    country="Spain",
    season_label="2021/22",
    start_year=2021,
    league_table_file="league_table_2021-2022_LaLiga.txt",
    fixtures_file="fixtures_2021-2022_LaLiga.txt",
    standard_files=("standard_2021-2022_LaLiga.txt",),
    shooting_files=("shooting_2021-2022_LaLiga.txt",),
    defense_file="defense_2021-2022_LaLiga.txt",
    keepers_file="keepers_2021-2022_LaLiga.txt",
    roles_file="transfermarkt_roles_2021-2022_LaLiga.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2021_22,
    use_compact=True,
)


LA_LIGA_2020_21 = LeagueConfig(
    league_name="La Liga",
    country="Spain",
    season_label="2020/21",
    start_year=2020,
    league_table_file="league_table_2020-2021_LaLiga.txt",
    fixtures_file="fixtures_2020-2021_LaLiga.txt",
    standard_files=("standard_2020-2021_LaLiga.txt",),
    shooting_files=("shooting_2020-2021_LaLiga.txt",),
    defense_file="defense_2020-2021_LaLiga.txt",
    keepers_file="keepers_2020-2021_LaLiga.txt",
    roles_file="transfermarkt_roles_2020-2021_LaLiga.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2020_21,
    use_compact=True,
)


LA_LIGA_2019_20 = LeagueConfig(
    league_name="La Liga",
    country="Spain",
    season_label="2019/20",
    start_year=2019,
    league_table_file="league_table_2019-2020_LaLiga.txt",
    fixtures_file="fixtures_2019-2020_LaLiga.txt",
    standard_files=("standard_2019-2020_LaLiga.txt",),
    shooting_files=("shooting_2019-2020_LaLiga.txt",),
    defense_file="defense_2019-2020_LaLiga.txt",
    keepers_file="keepers_2019-2020_LaLiga.txt",
    roles_file="transfermarkt_roles_2019-2020_LaLiga.txt",
    roles_club_id_map=TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2019_20,
    use_compact=True,
)


CONFIGS = {
    "pl2324": PREMIER_LEAGUE_2023_24,
    "laliga2324": LA_LIGA_2023_24,
    "pl2223": PREMIER_LEAGUE_2022_23,
    "pl2122": PREMIER_LEAGUE_2021_22,
    "pl2021": PREMIER_LEAGUE_2020_21,
    "pl1920": PREMIER_LEAGUE_2019_20,
    "laliga2223": LA_LIGA_2022_23,
    "laliga2122": LA_LIGA_2021_22,
    "laliga2021": LA_LIGA_2020_21,
    "laliga1920": LA_LIGA_2019_20,
}


if __name__ == "__main__":
    import sys

    target = sys.argv[1] if len(sys.argv) > 1 else "pl2324"
    load(CONFIGS[target])
