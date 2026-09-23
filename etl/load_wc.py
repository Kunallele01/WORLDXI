"""
Loads World Cup mode's reference data into the tables from migration 0007.

    python load_wc.py            dry run: build, verify, print — writes nothing
    python load_wc.py --load     the same, then replace the wc_* reference rows

WHAT GOES IN. Six tournaments (2006-2026); one row per country in wc_nations,
under a single canonical name so 2006's "Czech Republic" and 2026's "Czechia"
are the same nation; one squad per (nation, rating edition) actually used; the
208 nation entries with their real group records; and all 424 real matches.

IDEMPOTENT. A load deletes the existing reference rows first (tournaments
cascade to entries and matches, nations to squads and players) and inserts
afresh. It refuses to run once any wc_runs row exists, because a user's picks
reference squad players and a reload would pull them out from under him.

VERIFIED BEFORE WRITING. The dry run asserts what the mode depends on: every
match's two teams are entries of its own tournament (Postgres cannot express
that constraint — see the migration), every level knockout score has a
shootout winner and only those do, that winner is one of the two teams, and
each group has exactly one bottom nation. After a load, row counts are read
back from Supabase and compared with what was built.
"""
from __future__ import annotations

import argparse
import json
import os
import string
import sys
from collections import Counter
from pathlib import Path

import requests
from dotenv import load_dotenv

import wc_fixtures
import wc_groups
import wc_nations
import wc_squads

load_dotenv(Path(__file__).parent / ".env")
BATCH = 500
EVENTS = Path(__file__).parent / "wikipedia" / "wc_events.json"


# ----------------------------------------------------------------------------
# Build
# ----------------------------------------------------------------------------

def build() -> dict:
    matches = wc_fixtures.with_shootout_scores(wc_fixtures.load_all())
    if not EVENTS.exists():
        raise SystemExit(f"{EVENTS} is missing — run wc_wikipedia.py first")
    events = json.loads(EVENTS.read_text(encoding="utf-8"))
    tournaments, entries, match_rows = [], [], []
    nations: set[str] = set()
    squads: dict[tuple[str, str], tuple[wc_squads.Player, ...]] = {}
    borrowed: list[str] = []

    for year in wc_fixtures.EDITIONS:
        teams = wc_fixtures.participants(matches, year)
        tournaments.append({
            "year": year,
            "team_count": len(teams),
            "rating_edition": wc_fixtures.RATING_EDITION[year],
        })

        # REAL letters, from the Wikipedia group pages (wc_wikipedia.py, which
        # already checked they agree with the groups rebuilt from fixtures).
        # They used to be guessed from match dates, which got Group A right and
        # most others wrong — and 2026's third-place table is keyed on them.
        letter_of = events["editions"][str(year)]["letters"]
        lettered = {}
        for group in wc_groups.groups_of(matches, year):
            letters = {letter_of[t] for t in group}
            if len(letters) != 1:
                raise SystemExit(f"{year}: group {group} spans letters {letters}")
            lettered[letters.pop()] = group

        for letter, group in sorted(lettered.items()):
            standings = wc_groups.table(matches, year, group)
            for position, s in enumerate(standings, start=1):
                nation = wc_nations.canonical_name(s.team)
                nations.add(nation)
                pool = wc_squads.pool_for(year, s.team)
                squad_key = None
                if pool.source_edition is not None:
                    squad_key = (nation, pool.source_edition)
                    picked = wc_squads.national_squad(pool.players, wc_squads.SQUAD_SIZE[year])
                    if squad_key in squads and _ids(squads[squad_key]) != _ids(picked):
                        raise SystemExit(
                            f"{squad_key} is wanted at two different sizes; "
                            "the squad table keys on (nation, edition) only"
                        )
                    squads[squad_key] = picked
                    if pool.borrowed:
                        borrowed.append(f"{year} {s.team} <- FIFA {pool.source_edition}")
                entries.append({
                    "year": year,
                    "nation": nation,
                    "squad_key": squad_key,
                    "fixture_name": s.team,
                    "group_label": letter,
                    "group_position": position,
                    "played": s.played,
                    "won": s.won,
                    "drawn": s.drawn,
                    "lost": s.lost,
                    "goals_for": s.goals_for,
                    "goals_against": s.goals_against,
                    "points": s.points,
                    "finished_bottom": position == len(standings),
                    "is_host": s.team in wc_fixtures.HOSTS[year],
                })

        for m in matches:
            if m.edition != year:
                continue
            match_rows.append({
                "year": year,
                "round": m.round,
                "match_date": m.date or None,
                "home": m.home,
                "away": m.away,
                "home_goals": m.home_goals,
                "away_goals": m.away_goals,
                "home_pens": m.pens_home,
                "away_pens": m.pens_away,
                "shootout_winner": m.shootout_winner,
            })

    return {
        "tournaments": tournaments,
        "nations": sorted(nations),
        "squads": squads,
        "entries": entries,
        "matches": match_rows,
        "borrowed": borrowed,
        "goals": events["goals"],
    }


def grid_columns(p: wc_squads.Player) -> dict:
    """EA's positional grid as deltas from his primary role, or all nulls. See wc_squads._with_grid."""
    grid = p.grid_map or {}
    out = {f"grid_{role.lower()}": grid.get(role) for role in wc_squads.GRID_COLUMNS}
    out["grid_edition"] = p.grid_edition
    return out


def _ids(players) -> tuple[str, ...]:
    return tuple(p.sofifa_id for p in players)


# ----------------------------------------------------------------------------
# Verify
# ----------------------------------------------------------------------------

def verify(data: dict) -> list[str]:
    problems: list[str] = []
    entry_names = {(e["year"], e["fixture_name"]) for e in data["entries"]}

    if len(data["matches"]) != 424:
        problems.append(f"expected 424 matches, built {len(data['matches'])}")

    for t in data["tournaments"]:
        n = sum(1 for e in data["entries"] if e["year"] == t["year"])
        if n != t["team_count"]:
            problems.append(f"{t['year']}: {n} entries for {t['team_count']} teams")
        bottoms = sum(1 for e in data["entries"] if e["year"] == t["year"] and e["finished_bottom"])
        if bottoms * wc_groups.GROUP_SIZE != t["team_count"]:
            problems.append(f"{t['year']}: {bottoms} bottom nations for {t['team_count']} teams")

    for m in data["matches"]:
        label = f"{m['year']} {m['round']} {m['home']} v {m['away']}"
        for side in ("home", "away"):
            if (m["year"], m[side]) not in entry_names:
                problems.append(f"{label}: {m[side]} is not an entry of {m['year']}")
        if m["home_goals"] is None or m["away_goals"] is None:
            problems.append(f"{label}: no score")
            continue
        level = m["home_goals"] == m["away_goals"]
        knockout = m["round"] != wc_groups.GROUP_ROUND
        winner = m["shootout_winner"]
        if knockout and level and winner is None:
            problems.append(f"{label}: level knockout with no shootout winner")
        if winner is not None and not (knockout and level):
            problems.append(f"{label}: shootout winner on a match that was not a level knockout")
        if winner is not None and winner not in (m["home"], m["away"]):
            problems.append(f"{label}: shootout winner '{winner}' played in neither side")

    for e in data["entries"]:
        if e["squad_key"] is None:
            problems.append(f"{e['year']} {e['fixture_name']}: no squad at all")

    return problems


def report(data: dict) -> None:
    squads = data["squads"]
    players = sum(len(s) for s in squads.values())
    print(f"tournaments {len(data['tournaments'])}, nations {len(data['nations'])}, "
          f"squads {len(squads)}, squad players {players}, "
          f"entries {len(data['entries'])}, matches {len(data['matches'])}")
    print(f"borrowed squads ({len(data['borrowed'])}): " + "; ".join(data["borrowed"]))

    sizes = Counter(len(s) for s in squads.values())
    print("squad sizes: " + ", ".join(f"{k}x{v}" for k, v in sorted(sizes.items())))
    short = sorted((len(s), k) for k, s in squads.items() if len(s) < 23)
    print("squads under 23: " + ", ".join(f"{k[0]} FIFA {k[1]} ({n})" for n, k in short))

    for key in (("England", "23"), ("Brazil", "07"), ("Egypt", "24"), ("Japan", "11")):
        squad = squads.get(key)
        if not squad:
            continue
        lines = Counter(p.line for p in squad)
        print(f"\n  {key[0]} FIFA {key[1]}: {len(squad)} players — "
              + ", ".join(f"{ln} {lines[ln]}" for ln in ("GK", "DEF", "MID", "ATT")))
        print("    " + ", ".join(f"{p.name} {p.rating:.0f}" for p in squad[:8]) + ", ...")
        print("    weakest: " + ", ".join(f"{p.name} {p.rating:.0f} {p.positions[0]}" for p in squad[-3:]))

    print("\n2026 groups as lettered:")
    for letter in string.ascii_uppercase[:12]:
        members = [e["fixture_name"] for e in data["entries"] if e["year"] == 2026 and e["group_label"] == letter]
        print(f"  {letter}: {', '.join(members)}")


# ----------------------------------------------------------------------------
# Load
# ----------------------------------------------------------------------------

class Rest:
    def __init__(self) -> None:
        self.url = os.environ["SUPABASE_URL"].rstrip("/") + "/rest/v1/"
        key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
        self.base = {"apikey": key, "Authorization": f"Bearer {key}", "Content-Type": "application/json"}

    def _call(self, method: str, path: str, prefer: str, **kwargs):
        headers = dict(self.base, Prefer=prefer)
        resp = requests.request(method, self.url + path, headers=headers, timeout=120, **kwargs)
        if not resp.ok:
            raise RuntimeError(f"{method} {path} -> {resp.status_code}: {resp.text[:500]}")
        return resp

    def insert(self, table: str, rows: list[dict]) -> list[dict]:
        out: list[dict] = []
        for i in range(0, len(rows), BATCH):
            out += self._call("POST", table, "return=representation", json=rows[i:i + BATCH]).json()
        return out

    def delete_all(self, table: str) -> None:
        self._call("DELETE", f"{table}?id=gt.0", "return=minimal")

    def table_exists(self, table: str) -> bool:
        return self.column_exists(table, "id")

    def column_exists(self, table: str, column: str) -> bool:
        """PostgREST answers 4xx for a table or column the schema does not have."""
        resp = requests.get(self.url + f"{table}?select={column}&limit=1", headers=self.base, timeout=60)
        return resp.ok

    def count(self, table: str) -> int:
        resp = self._call("GET", f"{table}?select=id&limit=1", "count=exact")
        return int(resp.headers["content-range"].split("/")[-1])


def load(data: dict) -> None:
    api = Rest()
    if api.count("wc_runs"):
        raise SystemExit("wc_runs has rows: a reload would delete squad players that user picks reference")

    # Tournaments cascade to entries and matches; nations then cascade to
    # squads and their players. Entries must go first: they reference nations
    # and squads without a cascade.
    api.delete_all("wc_tournaments")
    api.delete_all("wc_nations")

    tournament_id = {r["year"]: r["id"] for r in api.insert("wc_tournaments", data["tournaments"])}
    nation_id = {r["name"]: r["id"] for r in api.insert("wc_nations", [{"name": n} for n in data["nations"]])}

    squad_keys = sorted(data["squads"])
    squad_rows = api.insert("wc_squads", [
        {"nation_id": nation_id[n], "rating_edition": ed} for n, ed in squad_keys
    ])
    squad_id = {(r["nation_id"], r["rating_edition"]): r["id"] for r in squad_rows}

    # The grid columns only exist once migration_0009 has run.
    has_grids = api.column_exists("wc_squad_players", "grid_cb")
    player_rows = []
    for nation, edition in squad_keys:
        sid = squad_id[(nation_id[nation], edition)]
        for p in data["squads"][(nation, edition)]:
            player_rows.append({
                "squad_id": sid,
                "sofifa_id": p.sofifa_id or None,
                "full_name": p.name,
                "overall": p.overall,
                "rating": round(p.rating, 2),
                "positions": list(p.positions),
                "club": p.club or None,
                "birth_year": p.birth_year,
                **(grid_columns(p) if has_grids else {}),
            })
    api.insert("wc_squad_players", player_rows)

    has_host_column = api.column_exists("wc_nation_entries", "is_host")
    entry_rows = []
    for e in data["entries"]:
        key = e["squad_key"]
        entry_rows.append({
            "tournament_id": tournament_id[e["year"]],
            "nation_id": nation_id[e["nation"]],
            "squad_id": squad_id[(nation_id[key[0]], key[1])] if key else None,
            **{k: e[k] for k in (
                "fixture_name", "group_label", "group_position", "played", "won", "drawn",
                "lost", "goals_for", "goals_against", "points", "finished_bottom",
            )},
            # Only once migration_0008 has added the column.
            **({"is_host": e["is_host"]} if has_host_column else {}),
        })
    inserted = api.insert("wc_nation_entries", entry_rows)
    entry_id = {}
    year_of = {v: k for k, v in tournament_id.items()}
    for r in inserted:
        entry_id[(year_of[r["tournament_id"]], r["fixture_name"])] = r["id"]

    match_rows = api.insert("wc_matches", [
        {
            "tournament_id": tournament_id[m["year"]],
            "round": m["round"],
            "match_date": m["match_date"],
            "home_entry_id": entry_id[(m["year"], m["home"])],
            "away_entry_id": entry_id[(m["year"], m["away"])],
            "home_goals": m["home_goals"],
            "away_goals": m["away_goals"],
            "home_pens": m["home_pens"],
            "away_pens": m["away_pens"],
            "shootout_winner_entry_id": (
                entry_id[(m["year"], m["shootout_winner"])] if m["shootout_winner"] else None
            ),
        }
        for m in data["matches"]
    ])

    # Real scorers, once migration_0008 exists. A match is keyed by date as
    # well as teams because two nations can meet twice in one tournament
    # (Croatia and Morocco, 2022: group stage and third-place match).
    goals_loaded = None
    if api.table_exists("wc_match_goals"):
        match_id = {}
        for r, m in zip(match_rows, data["matches"]):
            match_id[(m["year"], m["match_date"], m["home"], m["away"])] = r["id"]
        goal_rows = [
            {
                "match_id": match_id[(g["edition"], g["date"], g["home"], g["away"])],
                "entry_id": entry_id[(g["edition"], g["team"])],
                "scorer": g["scorer"],
                "minute": g["minute"],
                "stoppage": g["stoppage"],
                "kind": g["kind"],
            }
            for g in data["goals"]
        ]
        api.insert("wc_match_goals", goal_rows)
        goals_loaded = len(goal_rows)
    else:
        print("\nwc_match_goals does not exist yet — run supabase/migration_0008_world_cup_goals.sql, "
              "then load again. Everything else is loaded.")

    expected = {
        "wc_tournaments": len(data["tournaments"]),
        "wc_nations": len(data["nations"]),
        "wc_squads": len(squad_keys),
        "wc_squad_players": len(player_rows),
        "wc_nation_entries": len(entry_rows),
        "wc_matches": len(data["matches"]),
    }
    if goals_loaded is not None:
        expected["wc_match_goals"] = goals_loaded
    print("\nread back from Supabase:")
    bad = False
    for table, want in expected.items():
        got = api.count(table)
        bad |= got != want
        print(f"  {table:20s} {got:5d}  (built {want}){'  MISMATCH' if got != want else ''}")
    if bad:
        raise SystemExit("row counts do not match what was built")
    print("LOAD VERIFIED")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--load", action="store_true", help="write to Supabase (default: dry run)")
    args = parser.parse_args()

    data = build()
    report(data)
    problems = verify(data)
    print()
    if problems:
        print(f"{len(problems)} PROBLEMS:")
        for p in problems[:40]:
            print("  - " + p)
        raise SystemExit(1)
    print("VERIFIED: every check passed")
    if args.load:
        load(data)
    else:
        print("dry run — nothing written (pass --load to write)")


if __name__ == "__main__":
    main()
