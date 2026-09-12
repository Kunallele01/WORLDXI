"""
Recomputes attributes (finishing/creation/carrying/buildup/defense/physical/
overall_rating/traits) for every already-loaded league-season using the
2026-08-29 DF/GK bias fix (see attributes.py module docstring), and PATCHes
only those columns on the existing player_season_stats rows.

Deliberately does NOT touch fixtures/players/club_seasons and does NOT
insert or delete any rows — pure column-level UPDATE on rows that already
exist, matched by (player full_name, club_season club_name). Safe to re-run.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import requests
from concurrent.futures import ThreadPoolExecutor
from dotenv import load_dotenv

APPLY = "--apply" in sys.argv
sys.stdout.reconfigure(encoding="utf-8")

from attributes import compute_all_attributes
from fifa_lookup import FifaLookup
from build_dataset import build_player_seasons, build_player_seasons_compact
from parse_raw import parse_league_table
from supabase_load import CONFIGS

load_dotenv(Path(__file__).parent / ".env")

# FIFA quality anchor, loaded once (see attributes.py "bias fix, part 3")
FIFA = FifaLookup()

SUPABASE_URL = os.environ["SUPABASE_URL"]
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
}


def get_all(table: str, params: dict) -> list[dict]:
    out, offset, page = [], 0, 1000
    while True:
        p = dict(params, limit=page, offset=offset)
        r = requests.get(f"{SUPABASE_URL}/rest/v1/{table}", headers=HEADERS, params=p)
        r.raise_for_status()
        batch = r.json()
        out.extend(batch)
        if len(batch) < page:
            break
        offset += page
    return out


total_updated = 0
total_unchanged = 0
total_unmatched = 0

for key, config in CONFIGS.items():
    print(f"\n=== {config.league_name} {config.season_label} ({key}) ===")

    league_table = parse_league_table(config.league_table_file)
    team_ga_per_game = {row.club: row.goals_against / row.played for row in league_table if row.played}
    if config.use_compact:
        seasons_data = build_player_seasons_compact(
            standard_file=config.standard_files[0],
            shooting_file=config.shooting_files[0],
            defense_file=config.defense_file,
            keepers_file=config.keepers_file,
            roles_file=config.roles_file,
            roles_club_id_map=config.roles_club_id_map,
        )
    else:
        seasons_data = build_player_seasons(
            standard_files=config.standard_files,
            shooting_files=config.shooting_files,
            defense_file=config.defense_file,
            keepers_file=config.keepers_file,
            roles_file=config.roles_file,
            roles_club_id_map=config.roles_club_id_map,
        )
    attrs = compute_all_attributes(seasons_data, team_ga_per_game=team_ga_per_game,
                                   fifa_lookup=FIFA, season_label=config.season_label)

    # Find this league+season's id, then its club_seasons and existing stat rows.
    league = get_all("leagues", {"select": "id", "name": f"eq.{config.league_name}"})[0]
    season = get_all("seasons", {
        "select": "id",
        "league_id": f"eq.{league['id']}",
        "label": f"eq.{config.season_label}",
    })[0]
    club_seasons = get_all("club_seasons", {
        "select": "id,club_name", "season_id": f"eq.{season['id']}",
    })
    cs_id_by_name = {c["club_name"]: c["id"] for c in club_seasons}
    cs_ids = ",".join(str(c["id"]) for c in club_seasons)

    existing_rows = get_all("player_season_stats", {
        "select": "id,player_id,club_season_id,primary_position,overall_rating,defense",
        "club_season_id": f"in.({cs_ids})",
    })
    player_ids = list({r["player_id"] for r in existing_rows})
    players_batch = []
    for i in range(0, len(player_ids), 200):
        chunk = ",".join(str(p) for p in player_ids[i : i + 200])
        players_batch.extend(get_all("players", {"select": "id,full_name", "id": f"in.({chunk})"}))
    name_by_player_id = {p["id"]: p["full_name"] for p in players_batch}
    cs_name_by_id = {c["id"]: c["club_name"] for c in club_seasons}

    updated = unchanged = unmatched = 0
    rating_deltas = []
    biggest_moves = []
    pending_patches: list[tuple[int, dict]] = []
    for row in existing_rows:
        player_name = name_by_player_id.get(row["player_id"])
        club_name = cs_name_by_id.get(row["club_season_id"])
        a = attrs.get((player_name, club_name))
        if a is None:
            unmatched += 1
            continue
        delta = a.overall_rating - (row.get("overall_rating") or 0)
        if delta != 0 or a.defense != row.get("defense"):
            rating_deltas.append(delta)
            biggest_moves.append((delta, player_name, club_name, row.get("overall_rating"), a.overall_rating))
        else:
            unchanged += 1

        if APPLY:
            pending_patches.append((row["id"], {
                "finishing": a.finishing, "creation": a.creation, "carrying": a.carrying,
                "buildup": a.buildup, "defense": a.defense, "physical": a.physical,
                "overall_rating": a.overall_rating, "traits": a.traits,
            }))
        updated += 1

    if APPLY and pending_patches:
        # PostgREST can't bulk-upsert here (player_season_stats.id is an
        # identity column defined GENERATED ALWAYS, so an on_conflict=id
        # upsert is rejected outright), and a single PATCH can only set one
        # value across all matched rows. So it's one request per row — run
        # them on a thread pool over a pooled Session instead of serially,
        # which is the difference between minutes and hours for ~5k rows.
        with requests.Session() as session:
            session.headers.update(HEADERS)
            adapter = requests.adapters.HTTPAdapter(pool_connections=16, pool_maxsize=16)
            session.mount("https://", adapter)

            def send(item):
                rid, patch = item
                resp = session.patch(
                    f"{SUPABASE_URL}/rest/v1/player_season_stats",
                    params={"id": f"eq.{rid}"}, json=patch, timeout=30,
                )
                resp.raise_for_status()

            with ThreadPoolExecutor(max_workers=16) as pool:
                for _ in pool.map(send, pending_patches):
                    pass

    mode = "PATCHED" if APPLY else "would patch (dry run)"
    print(f"  {mode}: {len(rating_deltas)} rows changed, {unchanged} unchanged, "
          f"{unmatched} unmatched (of {len(existing_rows)} existing rows)")
    if rating_deltas:
        avg_delta = sum(rating_deltas) / len(rating_deltas)
        print(f"  avg overall_rating delta among changed rows: {avg_delta:+.1f}")
    biggest_moves.sort(key=lambda x: abs(x[0]), reverse=True)
    for delta, name, club, old, new in biggest_moves[:5]:
        print(f"    {name:24s} {club:20s} OVR {old} -> {new} ({delta:+d})")

    total_updated += len(rating_deltas)
    total_unchanged += unchanged
    total_unmatched += unmatched

mode = "PATCHED" if APPLY else "WOULD PATCH (dry run — pass --apply to write)"
print(f"\n{mode}. {total_updated} rows changed, {total_unchanged} unchanged, "
      f"{total_unmatched} unmatched across all seasons.")
