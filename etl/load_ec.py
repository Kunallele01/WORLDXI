"""
Loads one European Cup season's reference data into the tables from migration 0011.

    python load_ec.py 2023            dry run: build, verify, print, writes nothing
    python load_ec.py 2023 --load     the same, then replace that season's ec_* rows

INPUTS (all built by the earlier steps; nothing is fetched here):
    wikipedia/ec_matches_<season>.json   ec_fixtures.py: 125 real matches + group tables
    ec/ec_players_<season>.tsv           ec_squads.py: 25-man rosters, 31 clubs
    ec/ec_strength_<season>.tsv          ec_strength.py: 32 strengths, one estimated

IDEMPOTENT PER SEASON. A load deletes that season's ec_seasons row first (which
cascades to its squads, players, entries and matches) and inserts afresh; clubs
are shared across seasons, so they are only ever added. It refuses to run once an
ec_run exists for the season, because a user's picks reference squad players.

VERIFIED BEFORE WRITING: 32 clubs and 8 groups of 4; every club has a strength
and every rostered club exactly 25 players; every match's two clubs are entries
of the season; one 4th-placed club per group (the takeover candidates); every
shootout belongs to a level knockout tie; played = 6 for every group entry. After
a load the row counts are read back from Supabase and compared with what was built.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).parent
BATCH = 500
GRID = ["cb", "fb", "dm", "cm", "cam", "winger", "st"]
STAGE_ORDER = ["group", "r16", "qf", "sf", "final", "winner"]


def build(season: int) -> dict:
    ms = json.loads((HERE / "wikipedia" / f"ec_matches_{season}.json").read_text(encoding="utf-8"))
    players = list(csv.DictReader(open(HERE / "ec" / f"ec_players_{season}.tsv", encoding="utf-8"), delimiter="\t"))
    strength = {r["club"]: r for r in csv.DictReader(open(HERE / "ec" / f"ec_strength_{season}.tsv", encoding="utf-8"), delimiter="\t")}
    matches = ms["matches"]
    clubs = sorted({m[s] for m in matches for s in ("home", "away")})

    # Record per club, and the furthest stage each reached.
    rec = {c: Counter() for c in clubs}
    reached = {c: "group" for c in clubs}
    for m in matches:
        for c, gf, ga in ((m["home"], m["home_goals"], m["away_goals"]), (m["away"], m["away_goals"], m["home_goals"])):
            if m["stage"] == "group":
                r = rec[c]
                r["played"] += 1; r["goals_for"] += gf; r["goals_against"] += ga
                r["won" if gf > ga else "drawn" if gf == ga else "lost"] += 1
                r["points"] += 3 if gf > ga else 1 if gf == ga else 0
            if STAGE_ORDER.index(m["stage"]) > STAGE_ORDER.index(reached[c]):
                reached[c] = m["stage"]
    final = next(m for m in matches if m["stage"] == "final")
    winner = final["home"] if final["home_goals"] > final["away_goals"] else final["away"]
    reached[winner] = "winner"

    entries = []
    for g, table in ms["group_tables"].items():
        for pos, (club, *_rest) in enumerate(table, 1):
            entries.append({"club": club, "group_label": g, "group_position": pos,
                            "finished_bottom": pos == 4, "reached_stage": reached[club],
                            **{k: rec[club][k] for k in ("played", "won", "drawn", "lost", "goals_for", "goals_against", "points")}})
    by_club = defaultdict(list)
    for p in players:
        by_club[p["club"]].append(p)
    return {"season": season, "clubs": clubs, "matches": matches, "entries": entries,
            "players": by_club, "strength": strength}


def verify(d: dict) -> list[str]:
    bad = []
    clubs, entries, matches = d["clubs"], d["entries"], d["matches"]
    if len(clubs) != 32:
        bad.append(f"{len(clubs)} clubs, expected 32")
    if {e["club"] for e in entries} != set(clubs):
        bad.append("entries and match clubs differ")
    groups = Counter(e["group_label"] for e in entries)
    if sorted(groups) != list("ABCDEFGH") or set(groups.values()) != {4}:
        bad.append(f"groups wrong: {groups}")
    if sum(e["finished_bottom"] for e in entries) != 8:
        bad.append("expected exactly 8 bottom clubs")
    for e in entries:
        if e["played"] != 6:
            bad.append(f"{e['club']} played {e['played']} group matches")
    for c in clubs:
        if c not in d["strength"]:
            bad.append(f"{c}: no strength")
        n = len(d["players"].get(c, []))
        if c in d["strength"] and d["strength"][c]["source"] == "squad_top14" and n != 25:
            bad.append(f"{c}: {n} players, expected 25")
        if c in d["strength"] and d["strength"][c]["source"] != "squad_top14" and n:
            bad.append(f"{c}: estimated strength but has players")
    for m in matches:
        if m["shootout"] and not (m["extra_time"] and m["stage"] != "group"):
            bad.append(f"shootout outside a knockout ET: {m}")
        if m["home"] == m["away"]:
            bad.append(f"same club both sides: {m}")
    if Counter(m["stage"] for m in matches) != {"group": 96, "r16": 16, "qf": 8, "sf": 4, "final": 1}:
        bad.append("match counts per stage wrong")
    return bad


def report(d: dict) -> None:
    print(f"{d['season']}/{d['season'] + 1}: {len(d['clubs'])} clubs, {len(d['matches'])} matches, "
          f"{sum(len(v) for v in d['players'].values())} players in {len(d['players'])} rostered squads")
    print("takeover candidates (4th in group): " + ", ".join(
        f"{e['group_label']}:{e['club']}" for e in d["entries"] if e["finished_bottom"]))
    print("reached: " + ", ".join(f"{s} {sum(e['reached_stage'] == s for e in d['entries'])}" for s in STAGE_ORDER))
    est = [c for c, r in d["strength"].items() if r["source"] != "squad_top14"]
    print("estimated strengths: " + ", ".join(f"{c} {d['strength'][c]['strength']} ({d['strength'][c]['source']})" for c in est))


class Rest:
    def __init__(self) -> None:
        self.url = os.environ["SUPABASE_URL"].rstrip("/") + "/rest/v1/"
        key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
        # New-style keys ("sb_secret_...") are not JWTs: they go in the apikey header
        # alone. The legacy service_role key is a JWT and also goes in Authorization.
        self.base = {"apikey": key, "Content-Type": "application/json"}
        if not key.startswith("sb_"):
            self.base["Authorization"] = f"Bearer {key}"

    def _call(self, method: str, path: str, prefer: str, **kwargs):
        import requests
        resp = requests.request(method, self.url + path, headers=dict(self.base, Prefer=prefer), timeout=120, **kwargs)
        if not resp.ok:
            raise RuntimeError(f"{method} {path} -> {resp.status_code}: {resp.text[:500]}")
        return resp

    def insert(self, table: str, rows: list[dict]) -> list[dict]:
        out: list[dict] = []
        for i in range(0, len(rows), BATCH):
            out += self._call("POST", table, "return=representation", json=rows[i:i + BATCH]).json()
        return out

    def select(self, path: str) -> list[dict]:
        return self._call("GET", path, "").json()

    def delete(self, path: str) -> None:
        self._call("DELETE", path, "return=minimal")

    def count(self, table: str, where: str = "") -> int:
        resp = self._call("GET", f"{table}?select=id&limit=1{where}", "count=exact")
        return int(resp.headers["content-range"].split("/")[-1])


def num(v: str):
    return int(v) if v not in ("", None) else None


def load(d: dict) -> None:
    api, season = Rest(), d["season"]
    existing = api.select(f"ec_seasons?season_year=eq.{season}&select=id")
    if existing and api.count("ec_runs", f"&season_id=eq.{existing[0]['id']}"):
        raise SystemExit("ec_runs has rows for this season: a reload would delete squad players that picks reference")
    api.delete(f"ec_seasons?season_year=eq.{season}")          # cascades to squads, players, entries, matches

    sid = api.insert("ec_seasons", [{"season_year": season, "label": f"{season}/{str(season + 1)[2:]}",
                                     "format": "groups_8x4", "team_count": 32}])[0]["id"]
    have = {r["name"]: r["id"] for r in api.select("ec_clubs?select=id,name&limit=1000")}
    new = [c for c in d["clubs"] if c not in have]
    have.update({r["name"]: r["id"] for r in api.insert("ec_clubs", [{"name": c} for c in new])})

    squad_rows = api.insert("ec_squads", [{
        "club_id": have[c], "season_id": sid, "has_roster": c in d["players"],
        "team_strength": float(d["strength"][c]["strength"]), "strength_source": d["strength"][c]["source"],
        "strength_std_error": float(d["strength"][c]["std_error"]) if d["strength"][c]["std_error"] else None,
    } for c in d["clubs"]])
    squad_of = {r["club_id"]: r["id"] for r in squad_rows}

    player_rows = [{
        "squad_id": squad_of[have[c]], "sofifa_id": p["sofifa_id"] or None, "full_name": p["name"],
        "nationality": p["nationality"] or None, "overall": int(p["fc24_rating"]), "rating": float(p["rating"]),
        "rating_edition": p["rating_edition"], "positions": p["positions"].split(";"),
        "birth_year": num(p["birth_year"]),
        **{f"grid_{g}": num(p[f"pos_{g}"]) for g in GRID},
    } for c, ps in d["players"].items() for p in ps]
    api.insert("ec_squad_players", player_rows)

    entry_rows = api.insert("ec_entries", [{
        "season_id": sid, "club_id": have[e["club"]], "squad_id": squad_of[have[e["club"]]],
        **{k: e[k] for k in ("group_label", "group_position", "played", "won", "drawn", "lost",
                             "goals_for", "goals_against", "points", "finished_bottom", "reached_stage")},
    } for e in d["entries"]])
    entry_of = {r["club_id"]: r["id"] for r in entry_rows}

    def pens(m):
        if not m["shootout"]:
            return None, None
        a, b = (int(x) for x in m["shootout"].split("-"))
        return a, b
    match_rows = []
    for m in d["matches"]:
        hp, ap = pens(m)
        match_rows.append({
            "season_id": sid, "stage": m["stage"], "group_label": m["group"], "leg": m["leg"],
            "match_date": m["date"], "home_entry_id": entry_of[have[m["home"]]], "away_entry_id": entry_of[have[m["away"]]],
            "home_goals": m["home_goals"], "away_goals": m["away_goals"], "extra_time": m["extra_time"],
            "home_pens": hp, "away_pens": ap, "venue": m["venue"] or None,
        })
    api.insert("ec_matches", match_rows)

    expected = {"ec_squads": len(d["clubs"]), "ec_squad_players": len(player_rows),
                "ec_entries": len(entry_rows), "ec_matches": len(match_rows)}
    print("\nread back from Supabase (this season):")
    bad = False
    for table, want in expected.items():
        col = "season_id" if table in ("ec_squads", "ec_entries", "ec_matches") else None
        if table == "ec_squad_players":
            got = sum(api.count(table, f"&squad_id=eq.{s}") for s in squad_of.values())
        else:
            got = api.count(table, f"&{col}=eq.{sid}")
        bad |= got != want
        print(f"  {table:18s} {got:5d}  (built {want}){'  MISMATCH' if got != want else ''}")
    if bad:
        raise SystemExit("row counts do not match what was built")
    print("LOAD VERIFIED")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("season", type=int)
    ap.add_argument("--load", action="store_true", help="write to Supabase (default: dry run)")
    args = ap.parse_args()
    d = build(args.season)
    report(d)
    problems = verify(d)
    print()
    if problems:
        print(f"{len(problems)} PROBLEMS:")
        for p in problems[:40]:
            print("  - " + p)
        raise SystemExit(1)
    print("VERIFIED: every check passed")
    if args.load:
        from dotenv import load_dotenv
        load_dotenv(HERE / ".env")
        load(d)
    else:
        print("dry run — nothing written (pass --load to write)")


if __name__ == "__main__":
    main()
