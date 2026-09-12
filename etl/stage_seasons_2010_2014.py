"""
Stages complete player-seasons for 2009/10-2013/14, PL and La Liga.

Writes etl/staged/playerseasons_*.tsv. TOUCHES NO DATABASE.

Built on stage_seasons_2015_2019 rather than copied from it: the archive
reader, name normalisation, club agreement and output schema are imported, so
the two ranges cannot drift apart. What differs is what has to:

NO UNDERSTAT. Understat's history starts at 2014/15, so there is no xG, xA,
npxG or key-pass count for these seasons at any public source. The engine's
attack model runs on npxG, so these seasons carry a PROXY in the npxg column:

    npxG/90 = 0.0052 + 0.1579 * shots_on_target/90 + 0.3998 * goals/90

fitted on 2014/15-2018/19 only and validated blind on 159 club-seasons from
2019/20-2023/24 through the full engine pipeline (etl/sim_defence_fit.py
constants, Poisson points): league-points r = 0.904 / RMSE 7.5, against
0.898 / 8.1 using real npxG. Total shots are not in the proxy because the
archive has no total shots before 2015/16 — shots on target are complete.

Part of the proxy's edge is that it contains real goals, so it partly fits the
outcome. That is fine for simulating a season and wrong for claiming it
measures chance quality, so xg and xa stay NULL: nothing downstream should be
able to mistake the proxy for Understat's number.

WHAT IS NULL FOR THESE SEASONS, deliberately, never zero: shots (total), xg,
xa, key_passes, tackles, interceptions. The archive has none of them, and a
zero would claim the player made none.

TWO GAPS FILLED, both measured and approved by the user on 2026-09-12:
  * positional grids for players who retired before FIFA 15 — see
    position_template();
  * ratings for regulars the FIFA anchor could not find — see
    CalibratedRating.
"""
from __future__ import annotations

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

from attributes import compute_all_attributes
from build_dataset import PlayerSeason
from fifa_lookup import SLOT_KEYS, FifaLookup
from rating_scale import to_modern_scale
from roles import FALLBACK_GROUP_BY_FBREF_TAG
from stage_seasons_2015_2019 import (
    FIFA_POS_TO_GROUP, LEAGUES, OUT_FIELDS, STAGED, _int, _num, load_archive, load_table, norm,
)

HERE = Path(__file__).parent
SEASONS = {f"{y}/{str(y + 1)[2:]}": y + 1 for y in range(2009, 2014)}

# Validated coefficients — see the module docstring. Per 90 minutes.
PROXY_INTERCEPT = 0.0052
PROXY_SOT = 0.1579
PROXY_GOALS = 0.3998

# Older sofifa editions use a few position tokens the modern archive never
# does. Same meaning, mapped to the same groups.
POS_TO_GROUP = dict(FIFA_POS_TO_GROUP, LF="ST", RF="ST", LAM="CAM", RAM="CAM")

ROLE_TO_SLOT = {"CB": "cb", "FB": "fb", "DM": "dm", "CM": "cm", "CAM": "cam", "Winger": "winger", "ST": "st"}
EA_POS_TO_SLOT = {"CB": "cb", "LB": "fb", "RB": "fb", "LWB": "fb", "RWB": "fb", "CDM": "dm", "CM": "cm",
                  "CAM": "cam", "LM": "winger", "RM": "winger", "LW": "winger", "RW": "winger",
                  "ST": "st", "CF": "st"}


def position_template() -> dict[tuple[str, str], float]:
    """
    Typical out-of-position penalty by (own slot, target slot), from real EA grids.

    WHY THIS EXISTS. Editions 10-14 publish no positional grid, so a player's is
    borrowed by sofifa id from FIFA 15-18 — which leaves everyone who retired
    before FIFA 15 with none at all: 415 outfield regulars, 36 of them rated
    80+ (Puyol in four seasons, Ballack, Anelka, Carragher, Giggs, Deco). In the
    app a missing grid makes coverDelta return 0 — NO out-of-position penalty
    anywhere, so 2009/10 Puyol would play up front at full strength.

    The median penalty for a player's own position is a far better guess than
    zero. Fitted on FIFA 15-19 and scored on FIFA 20-24, which it never saw:
    mean error 2.95 rating points, against 8.43 for no grid (centre-backs 3.6 vs
    12.4, strikers 3.1 vs 12.8). Its known cost: a genuinely versatile player
    (Carragher: CB/RB/LB/DM) gets his position's typical versatility, not his
    own.

    Same fitting window as that validation (editions 15-19), recomputed from the
    table each run rather than pasted in, so it cannot drift from the data.
    """
    samples: dict[tuple[str, str], list[int]] = defaultdict(list)
    with open(HERE / "fifa" / "fifa_ratings_normalized.csv", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if not r["edition"].isdigit() or not 15 <= int(r["edition"]) <= 19 or not r["pos_cb"]:
                continue
            own = next((EA_POS_TO_SLOT[p] for p in r["positions"].split(";") if p in EA_POS_TO_SLOT), None)
            if own is None or not r[f"pos_{own}"]:
                continue
            base = int(r[f"pos_{own}"])
            for k in SLOT_KEYS:
                if r[f"pos_{k}"]:
                    samples[(own, k)].append(min(0, int(r[f"pos_{k}"]) - base))
    return {key: statistics.median(v) for key, v in samples.items()}


def template_grid(template: dict, role: str, overall: int) -> dict[str, int]:
    """
    A grid whose DELTAS are the template's, anchored on the player's own rating.

    Only the differences matter downstream — coverDelta subtracts the value at
    the player's own position — so the anchor changes nothing about a penalty.
    """
    own = ROLE_TO_SLOT[role]
    return {k: int(round(overall + template[(own, k)])) for k in SLOT_KEYS}


class CalibratedRating:
    """
    A FIFA-scale rating estimated from box-score stats, for regulars the FIFA
    anchor could not find.

    WHY. 26 regulars across these ten seasons have no FIFA match, mostly
    players who moved to a league the sofifa scrape did not cover before the
    next edition shipped (Lucas Neill to Galatasaray). attributes.py's stats-only
    fallback rates them absurdly — Djibril Cisse 49, El Hadji Diouf 49 — which a
    top-flight regular is not.

    Instead: a per-position-group linear fit of the REAL FIFA-anchored rating on
    stats these seasons have — club points per game, share of the season played,
    goals, assists, shots on target and yellows per 90, age and age squared, save
    percentage. Fitted on the staged 2014/15-2018/19 regulars, whose ratings are
    FIFA-anchored. The same shape validated on held-out seasons at RMSE about
    2.8, 84% within three points; it underrates genuine stars by about two
    points (regression to the mean), which barely touches these players.
    Applied ONLY to 900-minute regulars, the population it was fitted on.
    """

    GROUP = {"GK": "GK", "CB": "DEF", "FB": "DEF", "DM": "MID", "CM": "MID",
             "CAM": "MID", "Winger": "ATT", "ST": "ATT"}

    def __init__(self) -> None:
        data: dict[str, tuple[list, list]] = defaultdict(lambda: ([], []))
        for league in LEAGUES:
            for year in range(2014, 2019):
                season = f"{year}/{str(year + 1)[2:]}"
                tag = f"{league}_{season.replace('/', '-')}"
                table = load_table(league, season)
                with open(STAGED / f"playerseasons_{tag}.tsv", encoding="utf-8", newline="") as fh:
                    rows = list(csv.DictReader(fh, delimiter="\t"))
                pts = {t["club_name"]: int(t["points"]) for t in table}
                for r in rows:
                    if (r["rating_source"] != "fifa+form" or int(r["minutes"]) < 900
                            or r["club_name"] not in pts or not r["overall_rating"]):
                        continue
                    X, y = data[self.GROUP.get(r["primary_position"], "MID")]
                    X.append(self.features(pts[r["club_name"]], r, year))
                    y.append(float(r["overall_rating"]))
        self.coef = {g: self._ols(X, y) for g, (X, y) in data.items()}
        self.fitted_on = {g: len(y) for g, (_X, y) in data.items()}

    @staticmethod
    def features(points: int, r: dict, start_year: int) -> list[float]:
        minutes = int(r["minutes"])
        per90 = minutes / 90.0
        age = start_year - int(r["birth_year"])
        save = _num(r.get("save_pct"), 69.0)
        return [1.0, points / 38.0, min(minutes, 3420) / 3420.0,
                int(r["goals"]) / per90, int(r["assists"]) / per90,
                (_int(r.get("shots_on_target"), 0) or 0) / per90,
                int(r["yellow_cards"]) / per90, float(age), (age - 26.0) ** 2, save]

    @staticmethod
    def _ols(X: list, y: list) -> list[float]:
        k = len(X[0])
        A = [[sum(a[i] * a[j] for a in X) + (1e-6 if i == j else 0.0) for j in range(k)] for i in range(k)]
        b = [sum(a[i] * t for a, t in zip(X, y)) for i in range(k)]
        for i in range(k):
            piv = A[i][i]
            A[i] = [v / piv for v in A[i]]
            b[i] /= piv
            for r in range(k):
                if r != i:
                    f = A[r][i]
                    A[r] = [vr - f * vi for vr, vi in zip(A[r], A[i])]
                    b[r] -= f * b[i]
        return b

    def estimate(self, points: int, r: dict, start_year: int) -> int:
        coef = self.coef[self.GROUP.get(r["primary_position"], "MID")]
        value = sum(c * x for c, x in zip(coef, self.features(points, r, start_year)))
        return int(round(min(max(value, 40.0), 95.0)))


def proxy_npxg(goals: int, shots_on_target: int | None, minutes: int, group: str) -> float | str:
    """Season-total npxG estimate, or '' when the inputs to estimate it are missing."""
    if group == "GK":
        return 0.0
    if minutes <= 0 or shots_on_target is None:
        return ""
    per90 = minutes / 90.0
    rate = PROXY_INTERCEPT + PROXY_SOT * shots_on_target / per90 + PROXY_GOALS * goals / per90
    return round(max(0.0, rate) * per90, 3)


def build(league: str, season: str, archive, fifa: FifaLookup, template: dict,
          calibrated: CalibratedRating):
    end_year = SEASONS[season]
    comp = LEAGUES[league]

    def rows_of(name):
        df = archive[name]
        return df[(df.Season_End_Year.astype(float) == end_year) & (df.Comp == comp)].to_dict("records")

    std = rows_of("standard")
    shooting = {(norm(r["Player"]), r["Squad"]): r for r in rows_of("shooting")}
    keepers = {(norm(r["Player"]), r["Squad"]): r for r in rows_of("keepers")}

    table = load_table(league, season)
    played = {t["club_name"]: int(t["wins"]) + int(t["draws"]) + int(t["losses"]) for t in table}
    team_ga = {t["club_name"]: int(t["goals_against"]) / max(played[t["club_name"]], 1) for t in table}
    points = {t["club_name"]: int(t["points"]) for t in table}

    stats = {"rows": 0, "fifa": 0, "grid": 0, "no_fifa": [], "no_grid_900": 0, "regulars": 0,
             "templated": 0, "calibrated": 0}
    seasons_list: list[PlayerSeason] = []
    meta: dict = {}
    for r in std:
        name, club = r["Player"], r["Squad"]
        birth_year = _int(r["Born"])
        if birth_year is None:
            continue
        minutes = _int(r["Min_Playing"], 0) or 0
        key = (norm(name), club)
        sh, gk = shooting.get(key), keepers.get(key)

        profile, _tier = fifa.get_profile(name, birth_year, season, club)
        if profile is None:
            if minutes >= 900:
                stats["no_fifa"].append(f"{name} ({club}, {minutes}min)")
        else:
            stats["fifa"] += 1

        group = None
        if profile and profile.positions:
            for p in profile.positions:
                if p in POS_TO_GROUP:
                    group = POS_TO_GROUP[p]
                    break
        if group is None:
            group = FALLBACK_GROUP_BY_FBREF_TAG.get(str(r["Pos"] or "").split(",")[0], "CM")

        sot = _int(sh["SoT_Standard"]) if sh else None
        ps = PlayerSeason(
            player=name, team=club,
            nationality=(profile.nationality if profile else ""),
            position=str(r["Pos"] or ""), role_group=group,
            age=_int(r["Age"], 0) or 0, birth_year=birth_year,
            appearances=_int(r["MP_Playing"], 0) or 0, minutes=minutes,
            goals=_int(r["Gls"], 0) or 0, assists=_int(r["Ast"], 0) or 0,
            yellow_cards=_int(r["CrdY"], 0) or 0, red_cards=_int(r["CrdR"], 0) or 0,
            shots=0, shots_on_target=sot or 0,
            tackles_won=0, interceptions=0,
            gk_saves=_int(gk["Saves"]) if gk else None,
            gk_save_pct=_num(gk["Save_percent"]) if gk else None,
            gk_clean_sheets=_int(gk["CS"]) if gk else None,
            gk_goals_against=_int(gk["GA"]) if gk else None,
        )
        seasons_list.append(ps)
        meta[(name, club)] = dict(profile=profile, keepers=gk, sot=sot, group=group,
                                  fbref_pos=str(r["Pos"] or ""))

    attrs = compute_all_attributes(seasons_list, team_ga, fifa_lookup=fifa, season_label=season)

    out: list[dict] = []
    for ps in seasons_list:
        m = meta[(ps.player, ps.team)]
        a = attrs.get((ps.player, ps.team))
        p, gk = m["profile"], m["keepers"]
        grid = (p.grid if p else {}) or {}
        if ps.minutes >= 900:
            stats["regulars"] += 1
            if not grid and m["group"] != "GK":
                stats["no_grid_900"] += 1
        if grid:
            stats["grid"] += 1
        row = {
            "league": LEAGUES[league], "season": season, "club_name": ps.team,
            "full_name": ps.player, "birth_year": ps.birth_year,
            "nationality": ps.nationality,
            "primary_position": m["group"],
            "secondary_positions": ";".join(p.positions) if p else "",
            "fbref_position": m["fbref_pos"],
            "appearances": ps.appearances, "minutes": ps.minutes,
            "goals": ps.goals, "assists": ps.assists,
            "yellow_cards": ps.yellow_cards, "red_cards": ps.red_cards,
            "shots": "", "shots_on_target": m["sot"] if m["sot"] is not None else "",
            "key_passes": "", "tackles": "", "interceptions": "",
            "saves": ps.gk_saves if gk else "",
            "save_pct": ps.gk_save_pct if gk else "",
            "clean_sheets": ps.gk_clean_sheets if gk else "",
            "xg": "", "xa": "",
            "npxg": proxy_npxg(ps.goals, m["sot"], ps.minutes, m["group"]),
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
            # Within migration_0006's vocabulary. The npxg in these rows is the
            # documented proxy, not Understat's, so "understat" would be false.
            "stats_source": "fbref_archive",
        }
        # Calibrated rating FIRST, so a template grid anchors on the corrected value.
        if ps.minutes >= 900 and row["rating_source"] != "fifa+form" and row["overall_rating"] != "":
            row["overall_rating"] = calibrated.estimate(points[ps.team], row, end_year - 1)
            row["rating_source"] = "stats_calibrated"
            stats["calibrated"] += 1
        # Every outfielder without a real grid gets the position template.
        if not grid and m["group"] != "GK" and row["overall_rating"] != "":
            anchor = p.overall if p else int(row["overall_rating"])
            grid = template_grid(template, m["group"], anchor)
            row["pos_rating_edition"] = "template"
            stats["templated"] += 1
        for k in SLOT_KEYS:
            row[f"pos_rating_{k}"] = grid.get(k, "")
        out.append(row)
    stats["rows"] = len(out)
    return out, stats


class PositionHintedLookup:
    """
    A FifaLookup that tells the matcher where FBref says each man played.

    Every lookup in these seasons — the rating attributes.py asks for through
    get(), and the full profile build() asks for — goes through the same
    position-guarded cascade (FifaLookup._fits), so a rating and a grid cannot
    end up coming from two different people.

    An adapter rather than a change to attributes.py on purpose: that module is
    shared with the loaded 2014/15-2023/24 pipeline, and those seasons should
    not silently change the next time anyone re-runs it.
    """

    def __init__(self, fifa: FifaLookup, hints: dict[tuple[str, str, str], str]):
        self._fifa = fifa
        self._hints = hints

    def __getattr__(self, name):
        return getattr(self._fifa, name)

    def get_profile(self, full_name: str, birth_year: int, season: str, club: str = ""):
        return self._fifa.get_profile(full_name, birth_year, season, club,
                                      position_hint=self._hints.get((full_name, club, season), ""))

    def get(self, full_name: str, birth_year: int, season: str, club: str = "") -> float | None:
        """
        The FIFA base attributes.py builds the rating on — ON THE FIFA 17+ SCALE.

        EA inflated ratings between FIFA 15 and 17, so a base from FIFA 11-15
        reads roughly two to three points below the same player's quality on
        the modern scale (rating_scale.py). Rescaling the base here, rather than
        the finished rating afterwards, keeps the form modifier honest: it ranks
        a player's form against the bases of his own season, and within one
        season those can come from two editions (N+1 and its fallback N).
        Approved by the user 2026-09-12.
        """
        profile, _ = self.get_profile(full_name, birth_year, season, club)
        return to_modern_scale(profile.edition, profile.overall) if profile else None


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    print("Loading archive + FIFA anchor (editions 10-15 needed)...")
    archive = load_archive()
    std = archive["standard"]
    hints = {}
    for r in std[std.Season_End_Year.astype(float).between(2010, 2014)].to_dict("records"):
        end = int(float(r["Season_End_Year"]))
        hints[(r["Player"], r["Squad"], f"{end - 1}/{str(end)[2:]}")] = str(r["Pos"] or "")
    fifa = PositionHintedLookup(FifaLookup(), hints)
    missing = {"10", "11", "12", "13", "14", "15"} - fifa.editions
    if missing:
        raise SystemExit(f"FIFA editions missing from the normalized table: {sorted(missing)} "
                         f"— run sofifa_editions.py first")
    template = position_template()
    calibrated = CalibratedRating()
    print(f"position template from {len(template)} (own, target) pairs; calibrated rating fitted on "
          f"{calibrated.fitted_on}")

    grand = defaultdict(int)
    for league in LEAGUES:
        for season in SEASONS:
            rows, st = build(league, season, archive, fifa, template, calibrated)
            tag = f"{league}_{season.replace('/', '-')}"
            with open(STAGED / f"playerseasons_{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
                w = csv.DictWriter(fh, OUT_FIELDS, delimiter="\t")
                w.writeheader()
                w.writerows(rows)
            n = max(st["rows"], 1)
            for k in ("rows", "fifa", "grid", "regulars", "no_grid_900", "templated", "calibrated"):
                grand[k] += st[k]
            grand["no_fifa"] += len(st["no_fifa"])
            print(f"{league:7s} {season}: {st['rows']:4d} rows | FIFA {st['fifa'] * 100 // n:3d}% | "
                  f"real grid {st['grid'] * 100 // n:3d}% | template grids {st['templated']:3d} | "
                  f"calibrated ratings {st['calibrated']}")
    n = max(grand["rows"], 1)
    print(f"\nTOTAL {grand['rows']} player-seasons | FIFA {grand['fifa'] * 100 // n}% | "
          f"{grand['templated']} template grids | {grand['calibrated']} calibrated ratings "
          f"({grand['no_fifa']} regulars had no FIFA anchor)")


if __name__ == "__main__":
    main()
