"""
Stages complete player-seasons for 2014/15-2018/19, PL and La Liga.

Writes etl/staged/*.tsv. TOUCHES NO DATABASE — every row here is a proposal
for tomorrow's load, not a write.

WHERE EACH FIELD COMES FROM, and why there is no scraping in this file:

  spine        wfr/big5_player_standard.rds     minutes, apps, goals, assists,
                                                cards, birth year, club, FBref
                                                position tag. Covers 2009/10+.
  shots        wfr/big5_player_shooting.rds     2016/17+ only; Understat fills
                                                the rest (see below).
  tackles/int  wfr/big5_player_misc.rds         NOT uniform: absent for both
                                                leagues in 2014/15, and for the
                                                PL in 2015/16. Left NULL, not
                                                zeroed — a zero would claim the
                                                player made none. That is the
                                                exact trap the interceptions
                                                backfill had to undo.
  keepers      wfr/big5_player_keepers.rds      saves, save%, clean sheets.
                                                Complete for all five seasons.
  xG/xA/npxG/  understat_players_2015_2019.tsv  complete for all five seasons,
  key passes                                    both leagues. Also the fallback
                                                source for shots.
  rating+grid  fifa/fifa_ratings_normalized.csv editions 16-20, mapped N+1.

THE SEASONS ARE NOT EQUALLY RICH, and that is recorded rather than papered
over. Every season has the columns the simulation actually reads (minutes,
goals, assists, cards, shots, xG, npxG, xA, key passes, saves, clean
sheets). What varies is tackles/interceptions, which the engine does not
read at all and which are provably inverted as a quality signal anyway.
"""
from __future__ import annotations

import csv
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

import pyreadr

from attributes import compute_all_attributes
from build_dataset import PlayerSeason
from fifa_lookup import SLOT_KEYS, FifaLookup, clubs_agree
from roles import FALLBACK_GROUP_BY_FBREF_TAG, _normalize

HERE = Path(__file__).parent
STAGED = HERE / "staged"
STAGED.mkdir(exist_ok=True)

LEAGUES = {"PL": "Premier League", "LaLiga": "La Liga"}
SEASONS = {f"{y}/{str(y + 1)[2:]}": y + 1 for y in range(2014, 2019)}

# EA position token -> our role group. Deriving the role from the SAME matched
# EA row that supplies the positional grid means grid and recorded position
# cannot disagree for these seasons — which is exactly the fault (C) that put
# Dembele down as a striker in the seasons loaded from Transfermarkt.
FIFA_POS_TO_GROUP = {
    "GK": "GK", "CB": "CB", "LB": "FB", "RB": "FB", "LWB": "FB", "RWB": "FB",
    "CDM": "DM", "CM": "CM", "CAM": "CAM",
    "LM": "Winger", "RM": "Winger", "LW": "Winger", "RW": "Winger",
    "ST": "ST", "CF": "ST",
}

_UNDECOMPOSED = {ord("đ"): "d", ord("Đ"): "D", ord("ł"): "l", ord("Ł"): "L"}


def norm(name: str) -> str:
    """Accent- and stroke-insensitive key. Same normalisation as the rich-stats backfill."""
    return _normalize(unicodedata.normalize("NFC", name).translate(_UNDECOMPOSED))


def same_club(a: str, b: str) -> bool:
    """
    clubs_agree, plus an exact-name escape hatch.

    clubs_agree drops uninformative tokens ("fc", "real", "athletic",
    "club", ...) before comparing, which is right for "Deportivo Alaves" vs
    "Alaves" but leaves ATHLETIC CLUB with an empty token set on both sides —
    so Bilbao failed to match itself, losing every one of its 129 player-
    seasons' xG. Comparing the normalised names directly costs nothing and
    catches any other club whose whole name is stopwords.
    """
    return clubs_agree(a, b) or _normalize(a) == _normalize(b)


def _num(v, default=None):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    return default if f != f else f


def _int(v, default=None):
    f = _num(v)
    return default if f is None else int(round(f))


def load_archive() -> dict:
    out = {}
    for name in ("standard", "shooting", "misc", "keepers"):
        df = list(pyreadr.read_r(str(HERE / "wfr" / f"big5_player_{name}.rds")).values())[0]
        out[name] = df[df.Comp.isin(LEAGUES.values())]
    return out


def load_understat() -> tuple[dict, dict]:
    """
    Two indexes over the same rows: by exact name, and by club.

    The club index exists because Understat and FBref disagree about name
    FORM far more often than about identity — "Thievy" against "Thievy
    Bifouma", "Michel" against "Michel Macedo". Spanish football is full of
    mononyms, which is why La Liga matched some 12 points worse than the PL
    on exact names alone. Within one confirmed club the candidate pool is
    about 27 men, so a looser rule is safe there in a way it never is
    league-wide.
    """
    path = HERE / "understat" / "understat_players_2015_2019.tsv"
    by_name = defaultdict(list)
    by_club = defaultdict(list)
    with open(path, encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            by_name[(r["season"], r["league"], norm(r["player_name"]))].append(r)
            # A comma means Understat merged a mid-season transfer into a
            # single row ("Alaves,Athletic Club"). We hold one row per club,
            # and splitting by minutes would assume equal form at both, so
            # those are deliberately left out of the club index.
            if "," not in r["team_title"]:
                by_club[(r["season"], r["league"], _normalize(r["team_title"]))].append(r)
    return by_name, by_club


def find_in_club(name: str, club_rows: list[dict], goals: int | None = None) -> dict | None:
    """
    One Understat row for this player from his own club's squad, or None.

    Accepts a candidate only when it is UNIQUE, and only when its goal count
    corroborates the identity. Uniqueness alone is not enough: Swansea's 2017/18
    squad contains ONE Ayew in Understat (Jordan, 7 goals) and a different one
    in FBref (Andre, who left in January), so the surname rule matched them to
    each other and would have handed Andre his brother's xG. Both sources count
    the same league goals for the same man, so a gap of more than two is proof
    of a different person — the one check that catches a plausible-looking
    wrong match.
    """
    target = set(norm(name).split())
    if not target:
        return None

    def corroborated(r: dict) -> bool:
        if goals is None:
            return True
        try:
            return abs(int(r["goals"]) - goals) <= 2
        except (TypeError, ValueError):
            return False

    hits = []
    for r in club_rows:
        cand = set(norm(r["player_name"]).split())
        if cand and (cand <= target or target <= cand):
            hits.append(r)
    if len(hits) == 1 and corroborated(hits[0]):
        return hits[0]
    surname = norm(name).split()[-1]
    hits = [r for r in club_rows if norm(r["player_name"]).split()[-1:] == [surname]]
    return hits[0] if len(hits) == 1 and corroborated(hits[0]) else None


def load_table(league: str, season: str) -> list[dict]:
    tag = f"{league}_{season.replace('/', '-')}"
    with open(STAGED / f"table_{tag}.tsv", encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


OUT_FIELDS = [
    "league", "season", "club_name", "full_name", "birth_year", "nationality",
    "primary_position", "secondary_positions", "fbref_position",
    "appearances", "minutes", "goals", "assists", "yellow_cards", "red_cards",
    "shots", "shots_on_target", "key_passes", "tackles", "interceptions",
    "saves", "save_pct", "clean_sheets",
    "xg", "xa", "npxg", "overall_rating", "rating_source",
    "finishing", "creation", "carrying", "buildup", "defense", "physical",
    "position_side", "pos_rating_edition", "stats_source",
] + [f"pos_rating_{k}" for k in SLOT_KEYS]


def build(league: str, season: str, archive, by_name, by_club, fifa):
    end_year = SEASONS[season]
    comp = LEAGUES[league]

    def rows_of(name):
        df = archive[name]
        sel = df[(df.Season_End_Year.astype(float) == end_year) & (df.Comp == comp)]
        return sel.to_dict("records")

    std = rows_of("standard")
    shooting = {(norm(r["Player"]), r["Squad"]): r for r in rows_of("shooting")}
    misc = {(norm(r["Player"]), r["Squad"]): r for r in rows_of("misc")}
    keepers = {(norm(r["Player"]), r["Squad"]): r for r in rows_of("keepers")}

    table = load_table(league, season)
    played = {t["club_name"]: int(t["wins"]) + int(t["draws"]) + int(t["losses"]) for t in table}
    team_ga = {t["club_name"]: int(t["goals_against"]) / max(played[t["club_name"]], 1)
               for t in table}

    stats = {"rows": 0, "fifa": 0, "understat": 0, "no_fifa": [], "no_understat": 0}

    seasons_list: list[PlayerSeason] = []
    meta: dict = {}
    for r in std:
        name, club = r["Player"], r["Squad"]
        birth_year = _int(r["Born"])
        if birth_year is None:
            continue
        minutes = _int(r["Min_Playing"], 0) or 0

        key = (norm(name), club)
        sh, ms, gk = shooting.get(key), misc.get(key), keepers.get(key)

        # Understat, matched on name within this league-season and CONFIRMED
        # by club. A wrong club here would silently graft one man's xG onto
        # another's row, so an unconfirmed candidate is dropped, not guessed.
        us = None
        for cand in by_name.get((season, comp, norm(name)), []):
            # Skip Understat's merged transfer rows. clubs_agree happily
            # matches "Arsenal,Chelsea" against "Arsenal", which would staple
            # Giroud's whole 953-minute season onto his 388 Arsenal minutes
            # and inflate every per-90 rate we derive from it. Same policy as
            # the modern seasons, which skipped these too.
            if "," in cand["team_title"]:
                continue
            if same_club(cand["team_title"], club):
                us = cand
                break
        if us is None:
            # Same club, looser name. See find_in_club.
            for key, club_rows in by_club.items():
                if key[0] == season and key[1] == comp and same_club(key[2], club):
                    us = find_in_club(name, club_rows, _int(r["Gls"], 0) or 0)
                    break
        if us is None:
            stats["no_understat"] += 1
        else:
            stats["understat"] += 1

        profile, _tier = fifa.get_profile(name, birth_year, season, club)
        if profile is None:
            if minutes >= 900:
                stats["no_fifa"].append(f"{name} ({club}, {minutes}min)")
        else:
            stats["fifa"] += 1

        group = None
        if profile and profile.positions:
            for p in profile.positions:
                if p in FIFA_POS_TO_GROUP:
                    group = FIFA_POS_TO_GROUP[p]
                    break
        if group is None:
            group = FALLBACK_GROUP_BY_FBREF_TAG.get(str(r["Pos"] or "").split(",")[0], "CM")

        shots = _int(sh["Sh_Standard"]) if sh else None
        if shots is None and us is not None:
            shots = _int(us["shots"])

        ps = PlayerSeason(
            player=name, team=club,
            nationality=(profile.nationality if profile else ""),
            position=str(r["Pos"] or ""), role_group=group,
            age=_int(r["Age"], 0) or 0, birth_year=birth_year,
            appearances=_int(r["MP_Playing"], 0) or 0, minutes=minutes,
            goals=_int(r["Gls"], 0) or 0, assists=_int(r["Ast"], 0) or 0,
            yellow_cards=_int(r["CrdY"], 0) or 0, red_cards=_int(r["CrdR"], 0) or 0,
            shots=shots or 0,
            shots_on_target=(_int(sh["SoT_Standard"], 0) if sh else 0) or 0,
            tackles_won=(_int(ms["TklW"], 0) if ms else 0) or 0,
            interceptions=(_int(ms["Int"], 0) if ms else 0) or 0,
            gk_saves=_int(gk["Saves"]) if gk else None,
            gk_save_pct=_num(gk["Save_percent"]) if gk else None,
            gk_clean_sheets=_int(gk["CS"]) if gk else None,
            gk_goals_against=_int(gk["GA"]) if gk else None,
        )
        seasons_list.append(ps)
        meta[(name, club)] = dict(profile=profile, understat=us, misc=ms,
                                  shooting=sh, keepers=gk, shots=shots,
                                  group=group, fbref_pos=str(r["Pos"] or ""))

    attrs = compute_all_attributes(seasons_list, team_ga, fifa_lookup=fifa, season_label=season)

    out: list[dict] = []
    for ps in seasons_list:
        m = meta[(ps.player, ps.team)]
        a = attrs.get((ps.player, ps.team))
        p, us, ms, gk = m["profile"], m["understat"], m["misc"], m["keepers"]
        grid = (p.grid if p else {}) or {}
        # Tackles/interceptions stay NULL where the season carries no such
        # data, so a consumer can tell "no data" from "made none".
        has_def = ms is not None and _num(ms["Int"]) is not None
        row = {
            "league": comp, "season": season, "club_name": ps.team,
            "full_name": ps.player, "birth_year": ps.birth_year,
            "nationality": ps.nationality,
            "primary_position": m["group"],
            "secondary_positions": ";".join(p.positions) if p else "",
            "fbref_position": m["fbref_pos"],
            "appearances": ps.appearances, "minutes": ps.minutes,
            "goals": ps.goals, "assists": ps.assists,
            "yellow_cards": ps.yellow_cards, "red_cards": ps.red_cards,
            "shots": m["shots"] if m["shots"] is not None else "",
            "shots_on_target": ps.shots_on_target if m["shooting"] else "",
            "key_passes": _int(us["key_passes"]) if us else "",
            "tackles": _int(ms["TklW"]) if has_def else "",
            "interceptions": _int(ms["Int"]) if has_def else "",
            "saves": ps.gk_saves if gk else "",
            "save_pct": ps.gk_save_pct if gk else "",
            "clean_sheets": ps.gk_clean_sheets if gk else "",
            "xg": round(_num(us["xG"], 0.0), 3) if us else "",
            "xa": round(_num(us["xA"], 0.0), 3) if us else "",
            "npxg": round(_num(us["npxG"], 0.0), 3) if us else "",
            "overall_rating": a.overall_rating if a else "",
            "rating_source": a.rating_source if a else "",
            "finishing": a.finishing if a else "",
            "creation": a.creation if a else "",
            "carrying": a.carrying if a else "",
            "buildup": a.buildup if a else "",
            "defense": a.defense if a else "",
            "physical": a.physical if a else "",
            "position_side": (p.side or "") if p else "",
            "pos_rating_edition": (p.grid_edition or p.edition) if p else "",
            # migration_0006 constrains this to one of fbref_archive /
            # understat / fbref_live, and the loaded seasons use it to record
            # which tier supplied the RICH stats — the FBref spine is a given.
            # Inventing a combined value here failed the check constraint
            # mid-load; keep to the existing vocabulary.
            "stats_source": "understat" if us else "fbref_archive",
        }
        for k in SLOT_KEYS:
            row[f"pos_rating_{k}"] = grid.get(k, "")
        out.append(row)
    stats["rows"] = len(out)
    return out, stats


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    print("Loading archive + Understat + FIFA anchor...")
    archive = load_archive()
    by_name, by_club = load_understat()
    fifa = FifaLookup()

    grand = defaultdict(int)
    for league in LEAGUES:
        for season in SEASONS:
            rows, st = build(league, season, archive, by_name, by_club, fifa)
            tag = f"{league}_{season.replace('/', '-')}"
            with open(STAGED / f"playerseasons_{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
                w = csv.DictWriter(fh, OUT_FIELDS, delimiter="\t")
                w.writeheader()
                w.writerows(rows)
            grand["rows"] += st["rows"]
            grand["fifa"] += st["fifa"]
            grand["understat"] += st["understat"]
            n = max(st["rows"], 1)
            print(f"{league:7s} {season}: {st['rows']:4d} rows | "
                  f"FIFA {st['fifa'] * 100 // n:3d}% | "
                  f"Understat {st['understat'] * 100 // n:3d}% | "
                  f"no anchor at 900+min: {len(st['no_fifa'])}")
            for miss in st["no_fifa"][:3]:
                print(f"            {miss}")
    print(f"\nTOTAL {grand['rows']} player-seasons staged | "
          f"FIFA {grand['fifa'] * 100 // grand['rows']}% | "
          f"Understat {grand['understat'] * 100 // grand['rows']}%")


if __name__ == "__main__":
    main()
