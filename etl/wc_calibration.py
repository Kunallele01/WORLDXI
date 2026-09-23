"""
Measures every constant the World Cup knockout engine needs, from real World
Cup football. The sim package's rule is that no constant goes in unmeasured,
and a tournament brings several new ones.

    python wc_calibration.py

WHAT IS MEASURED, AND FROM WHAT
  1. Goals per team per match at a World Cup, group stage (every group match
     is exactly ninety minutes, so it is the clean sample).
  2. How often a knockout tie is level after ninety, and how often a tie that
     goes to extra time is still level after it (1986-2026; 1998 and 2002 are
     left out of the second because golden goal ended extra time at the first
     goal, which is a different question).
  3. Penalty conversion in shootouts, by maximum likelihood on the real
     shootout scores (only 2022 and 2026 record them).
  4. The rating cost of playing out of position, per role pair, from EA's
     positional grids (FIFA 15-24). World Cup players carry only a listed
     position, and without this table every one of them would play anywhere
     at no cost.
  5. Whether the CLUB-fitted strength model, fed rating-only World Cup
     squads, predicts real World Cup results — and the scale factors it needs
     for international, knockout and extra-time football.
  6. Host advantage, and whether the stronger side wins shootouts.

The strength model below is a line-for-line port of TeamStrength.kt and
MatchEngine.kt so the measurement is of the model that ships. The Kotlin
validation test replays the same matches through the real code.
"""
from __future__ import annotations

import csv
import math
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment, minimize, minimize_scalar
from scipy.special import i0e

from rating_scale import to_modern_scale

import pyreadr
import wc_fixtures
import wc_groups
import wc_nations
import wc_output
import wc_squads

HERE = Path(__file__).parent
ROOT = HERE.parent
FORMATION_KT = ROOT / "app/src/main/java/com/dreamxi/app/feature/draft/Formation.kt"
KNOCKOUT_ROUNDS = {"Round of 32", "Round of 16", "Quarter-finals", "Semi-finals",
                   "Third-place match", "Final", "Second round"}
GOLDEN_GOAL = {1998, 2002}

# ---------------------------------------------------------------- SimModel.kt
ATTACK_SLOPE, ATTACK_INTERCEPT = 0.8828, 0.2095
ATTACK_LINEAR_MAX, ATTACK_ASYMPTOTE = 3.00, 3.30
DEFENCE_LOG_SLOPE, DEFENCE_LOG_INTERCEPT = -0.07293, 5.9884
DEFENSIVE_WEIGHT = {"GK": 1.00, "CB": 1.00, "FB": 0.85, "DM": 0.70,
                    "CM": 0.45, "CAM": 0.20, "Winger": 0.20, "ST": 0.10}
SLOT_NPXG90 = {"GK": 0.0, "CB": 0.047, "FB": 0.034, "DM": 0.040,
               "CM": 0.073, "CAM": 0.161, "Winger": 0.193, "ST": 0.353}
SLOT_MEDIAN_OVR = {"GK": 79, "CB": 77, "FB": 77, "DM": 77,
                   "CM": 77, "CAM": 80, "Winger": 78, "ST": 79}
RATING_EDGE_PER_POINT = 0.030
POSITION_TRANSFER_RANGE = 25.0
LEAGUE_GOALS_PER_TEAM = 1.3505
MIN_XG, MAX_XG = 0.15, 5.0

EA_ROLE = wc_squads.EA_ROLE
GRID_KEY = {"CB": "pos_cb", "FB": "pos_fb", "DM": "pos_dm", "CM": "pos_cm",
            "CAM": "pos_cam", "Winger": "pos_winger", "ST": "pos_st"}
OUTFIELD = list(GRID_KEY)


def section(title: str) -> None:
    print("\n" + "=" * 78 + f"\n{title}\n" + "=" * 78)


# ============================================================================
# 1-2. Goal rates, extra time, shootouts — straight from results
# ============================================================================

def measure_results() -> dict:
    section("1. Goals per team per match (group stage, 90 minutes)")
    matches = wc_fixtures.with_shootout_scores(wc_fixtures.load_all())
    group = [m for m in matches if m.round == wc_groups.GROUP_ROUND]
    goals = [g for m in group for g in (m.home_goals, m.away_goals)]
    rate = statistics.mean(goals)
    draws = sum(m.home_goals == m.away_goals for m in group) / len(group)
    print(f"2006-2026: {len(group)} group matches, {rate:.4f} goals per team, draw rate {draws:.3f}")
    for ed in wc_fixtures.EDITIONS:
        g = [x for m in group if m.edition == ed for x in (m.home_goals, m.away_goals)]
        print(f"   {ed}: {statistics.mean(g):.3f}")
    print(f"club leagues (SimModel.LEAGUE_GOALS_PER_TEAM): {LEAGUE_GOALS_PER_TEAM}")

    section("2. Knockout ties: level after 90, and still level after extra time")
    mirror = list(pyreadr.read_r(str(wc_fixtures.MIRROR)).values())[0]
    # (edition, went to penalties, went to extra time). The mirror covers to
    # 2018; 2022 and 2026 come from the FBref TSV's notes column.
    notes = {}
    for row in csv.reader(open(wc_fixtures.RECENT, encoding="utf-8"), delimiter="	"):
        if len(row) > 9 and row[0].strip().isdigit():
            key = (int(row[0]), wc_nations.strip_country_code(row[3]), wc_nations.strip_country_code(row[6]))
            notes[key] = row[9]
    rows = []
    for r in mirror.to_dict("records"):
        if 1986 <= r["Season_End_Year"] <= 2018 and r["Round"] in KNOCKOUT_ROUNDS:
            note = str(r["Notes"] or "")
            rows.append((int(r["Season_End_Year"]), "penalty" in note, "Extra" in note or "penalty" in note))
    for m in matches:
        if m.edition >= 2022 and m.round in KNOCKOUT_ROUNDS:
            note = notes.get((m.edition, m.home, m.away), "")
            rows.append((m.edition, "penalty" in note, "Extra" in note or "penalty" in note))

    ko_total = len(rows)
    went_et = [r for r in rows if r[2]]
    print(f"knockout matches 1986-2026: {ko_total}; level after 90: {len(went_et)} "
          f"({len(went_et) / ko_total:.3f})")
    regular = [r for r in went_et if r[0] not in GOLDEN_GOAL]
    pens_share = sum(r[1] for r in regular) / len(regular)
    print(f"of those that went to extra time (golden-goal 1998/2002 excluded): "
          f"{len(regular)}, still level after ET: {sum(r[1] for r in regular)} ({pens_share:.3f})")
    by_ed = defaultdict(lambda: [0, 0, 0])
    for ed, pens, et in rows:
        by_ed[ed][0] += 1
        by_ed[ed][1] += bool(et)
        by_ed[ed][2] += bool(pens)
    print("   edition: knockouts / to extra time / to penalties")
    for ed in sorted(by_ed):
        print(f"   {ed}: {by_ed[ed][0]} / {by_ed[ed][1]} / {by_ed[ed][2]}")

    # Symmetric estimate: per-team ET goal rate x such that P(level) = observed.
    level = lambda x: i0e(2 * x)  # e^{-2x} I0(2x), the chance two Poisson(x) are equal
    x = minimize_scalar(lambda x: (level(x) - pens_share) ** 2, bounds=(0.01, 2.0), method="bounded").x
    print(f"symmetric estimate: {x:.3f} goals per team over 30 minutes of extra time "
          f"= {x * 3:.3f} per 90 (group-stage rate is {rate:.3f})")

    section("3. Shootout penalty conversion (all 23 shootout scores, 2006-2026)")
    scores = [(m.pens_home, m.pens_away) for m in matches if m.pens_home is not None]
    print(f"{len(scores)} shootouts: {scores}")
    p_hat, ci = shootout_conversion(scores)
    print(f"maximum likelihood conversion: {p_hat:.3f} (95% likelihood interval {ci[0]:.3f}-{ci[1]:.3f})")

    return {"matches": matches, "group_rate": rate, "ko_rows": rows, "pens_share": pens_share,
            "shootout_p": p_hat, "notes": notes}


def shootout_conversion(scores: list[tuple[int, int]]) -> tuple[float, tuple[float, float]]:
    """MLE of a single conversion rate, by exact simulation of the kick sequence."""
    def dist(p: float) -> dict[tuple[int, int], float]:
        out: dict[tuple[int, int], float] = defaultdict(float)
        frontier = {(0, 0, 0, 0): 1.0}
        for _ in range(60):
            nxt: dict[tuple[int, int, int, int], float] = defaultdict(float)
            for (a, b, ka, kb), pr in frontier.items():
                if pr < 1e-12:
                    continue
                a_turn = ka == kb
                for scored, q in ((1, p), (0, 1 - p)):
                    na, nb, nka, nkb = (a + scored, b, ka + 1, kb) if a_turn else (a, b + scored, ka, kb + 1)
                    if nka <= 5 and nkb <= 5:
                        done = na > nb + (5 - nkb) or nb > na + (5 - nka)
                    else:
                        done = nka == nkb and na != nb
                    if done:
                        out[(na, nb)] += pr * q
                    else:
                        nxt[(na, nb, nka, nkb)] += pr * q
            frontier = nxt
        return out

    def loglik(p: float) -> float:
        d = dist(p)
        total = 0.0
        for h, a in scores:
            # Who kicked first is not recorded, and the pair is symmetric in p.
            total += math.log(max(d.get((h, a), 0.0) + d.get((a, h), 0.0), 1e-300))
        return total

    grid = np.linspace(0.40, 0.95, 111)
    ll = np.array([loglik(p) for p in grid])
    best = grid[ll.argmax()]
    inside = grid[ll >= ll.max() - 1.92]
    return float(best), (float(inside.min()), float(inside.max()))


# ============================================================================
# 4. Out-of-position cost, from EA's grids
# ============================================================================

#: Rating bands the fallback costs are measured in, by the player's modern-scale
#: rating: under 75, 75-79, 80-84, 85 and up. Costs grow with quality — a
#: defensive midfielder under 75 loses 2 at full-back, one rated 85+ loses 4.
BAND_FLOORS = [0, 75, 80, 85]
#: A band's cell needs this many players to stand on its own; thinner cells
#: take the all-ratings median for that role pair instead.
MIN_BAND_CELL = 30
KOTLIN_COSTS = ROOT / "app/src/main/java/com/dreamxi/app/sim/worldcup/WcPositionCosts.kt"


def band_of(rating: float) -> int:
    return max(i for i, floor in enumerate(BAND_FLOORS) if rating >= floor)


def measure_position_costs() -> dict:
    """
    The FALLBACK cost of playing out of position, for the 13% of World Cup
    players with no positional grid in any edition. Everyone else is priced off
    his own EA grid (wc_squads._with_grid).

    Measured by rating band, because a single median understated what good
    players lose: that is how a 90-rated Rodri came to be priced at -2 at
    full-back when his own grid says -7.
    """
    section("4. Fallback out-of-position cost, by rating band (EA grids, FIFA 15-24)")
    unlisted: dict[tuple[int, str, str], list[int]] = defaultdict(list)
    unlisted_all: dict[tuple[str, str], list[int]] = defaultdict(list)
    listed: dict[tuple[int, str], list[int]] = defaultdict(list)
    listed_all: dict[str, list[int]] = defaultdict(list)
    with open(wc_squads.NORMALIZED, encoding="utf-8", errors="replace", newline="") as fh:
        for row in csv.DictReader(fh):
            positions = [p for p in (row.get("positions") or "").split(";") if p]
            if not positions or not row.get("pos_cb") or not (15 <= int(row["edition"]) <= 24):
                continue
            primary = EA_ROLE.get(positions[0])
            if primary is None or primary == "GK" or not row["overall"].isdigit():
                continue
            try:
                grid = {role: int(float(row[key])) for role, key in GRID_KEY.items()}
            except (TypeError, ValueError):
                continue
            band = band_of(to_modern_scale(row["edition"], int(row["overall"])))
            ref = grid[primary]
            listed_roles = {EA_ROLE.get(p) for p in positions[1:]} - {None, primary, "GK"}
            for target in OUTFIELD:
                if target == primary:
                    continue
                delta = min(0, grid[target] - ref)
                if target in listed_roles:
                    listed[(band, target)].append(delta)
                    listed_all[target].append(delta)
                else:
                    unlisted[(band, primary, target)].append(delta)
                    unlisted_all[(primary, target)].append(delta)

    def med(values: list[int]) -> int:
        return int(round(statistics.median(values)))

    unlisted_table: list[dict[str, int]] = []
    listed_table: list[dict[str, int]] = []
    for band, floor in enumerate(BAND_FLOORS):
        cells = {}
        for src in OUTFIELD:
            for dst in OUTFIELD:
                if src == dst:
                    continue
                vals = unlisted.get((band, src, dst), [])
                pool = vals if len(vals) >= MIN_BAND_CELL else unlisted_all.get((src, dst), [])
                cells[f"{src}>{dst}"] = med(pool) if pool else -25
        unlisted_table.append(cells)
        listed_table.append({
            dst: med(listed[(band, dst)] if len(listed[(band, dst)]) >= MIN_BAND_CELL else listed_all[dst])
            for dst in OUTFIELD if listed_all.get(dst)
        })
        print(f"\nband {floor}+ — rows: primary role, columns: target, for players NOT listing it")
        print("from\\to  " + "".join(f"{t:>8}" for t in OUTFIELD))
        for src in OUTFIELD:
            print(f"{src:>8} " + "".join(f"{(0 if src == dst else cells[f'{src}>{dst}']):>8}" for dst in OUTFIELD))
        print("   listed as a secondary position: " + ", ".join(f"{k} {v:+d}" for k, v in listed_table[-1].items()))

    write_kotlin_costs(unlisted_table, listed_table)
    return {"unlisted": unlisted_table, "listed": listed_table}


def write_kotlin_costs(unlisted_table: list[dict[str, int]], listed_table: list[dict[str, int]]) -> None:
    lines = [
        "package com.dreamxi.app.sim.worldcup",
        "",
        "// GENERATED by etl/wc_calibration.py (section 4). Do not edit by hand: rerun it.",
        "",
        "/**",
        " * The FALLBACK cost of playing out of position, for World Cup players with no",
        " * EA positional grid in any edition (13% of squad players, mostly FIFA 07",
        " * players who retired before FIFA 15). Everyone else is priced off his own",
        " * grid. Medians over FIFA 15-24 grids, by rating band, because the cost grows",
        f" * with quality; a band cell with fewer than {MIN_BAND_CELL} players uses the all-ratings median.",
        " */",
        "internal object WcPositionCosts {",
        "    /** Lower bound of each band, on the modern rating scale. */",
        "    val BAND_FLOORS: List<Int> = listOf(" + ", ".join(str(f) for f in BAND_FLOORS) + ")",
        "",
        '    /** Per band: "FROM>TO" -> rating change for a player who does NOT list TO. */',
        "    val UNLISTED: List<Map<String, Int>> = listOf(",
    ]
    for cells in unlisted_table:
        body = ", ".join(f'"{k}" to {v}' for k, v in cells.items())
        lines.append(f"        mapOf({body}),")
    lines += [
        "    )",
        "",
        "    /** Per band: role -> rating change for a player who lists it as a secondary position. */",
        "    val LISTED: List<Map<String, Int>> = listOf(",
    ]
    for cells in listed_table:
        body = ", ".join(f'"{k}" to {v}' for k, v in cells.items())
        lines.append(f"        mapOf({body}),")
    lines += [
        "    )",
        "",
        "    fun band(rating: Double): Int = BAND_FLOORS.indexOfLast { rating >= it }.coerceAtLeast(0)",
        "}",
        "",
    ]
    KOTLIN_COSTS.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nwrote {KOTLIN_COSTS.name}")


# ============================================================================
# 5. Strength model on rating-only national squads
# ============================================================================

def parse_formations() -> dict[str, list[tuple[str, str | None]]]:
    """Each formation's eleven (role, flank) pairs, as FormationSlot.side derives the flank."""
    text = FORMATION_KT.read_text(encoding="utf-8")
    text = text[text.index("val Formations"):]
    blocks = re.split(r"\n    Formation\(\n", text)[1:]

    def side(role: str, label: str) -> str | None:
        if role not in ("FB", "Winger"):
            return None
        return "L" if label.startswith("L") else "R" if label.startswith("R") else None

    out = {}
    for block in blocks:
        fid = re.search(r'"([^"]+)"', block).group(1)
        slots = [("GK", None)]
        if "backFour()" in block:
            slots += [("FB", "L"), ("CB", None), ("CB", None), ("FB", "R")]
        elif "backThree()" in block:
            slots += [("CB", None)] * 3
        elif "backFive()" in block:
            slots += [("FB", "L"), ("CB", None), ("CB", None), ("CB", None), ("FB", "R")]
        slots += [(role, side(role, label))
                  for role, label in re.findall(r'FormationSlot\("[^"]+", "([^"]+)", "([^"]+)"', block)]
        assert len(slots) == 11, (fid, slots)
        out[fid] = slots
    return out


#: The club draft's wrong-flank costs (DraftModels.kt), reused so the two modes
#: price a winger on the wrong side identically. A JUDGEMENT, not a measurement:
#: EA rates left and right identically for every player, so there is nothing to
#: fit. Applied to full-backs and wingers whose EA positions put them on one flank.
WRONG_SIDE_FULLBACK = 4
WRONG_SIDE_WINGER = 2
FLANK = {
    "FB": {"LB": "L", "LWB": "L", "RB": "R", "RWB": "R"},
    "Winger": {"LM": "L", "LW": "L", "RM": "R", "RW": "R"},
}


def player_side(player) -> str | None:
    primary = player.primary_role
    family = FLANK.get(primary)
    if family is None:
        return None
    sides = {family[p] for p in player.positions if p in family}
    return "B" if len(sides) == 2 else next(iter(sides), None)


def penalty(player, slot_role: str, slot_side: str | None, costs: dict) -> int | None:
    """Rating points lost in this slot: EA's own grid for him where there is one, the banded fallback otherwise."""
    primary = player.primary_role
    if slot_role == "GK" or primary == "GK":
        return 0 if slot_role == primary else None
    grid = player.grid_map
    if grid is not None:
        base = min(0, grid.get(slot_role, 0))
    elif slot_role == primary:
        base = 0
    else:
        band = band_of(player.rating)
        secondary = {EA_ROLE.get(p) for p in player.positions[1:]} - {None, primary}
        if slot_role in secondary:
            base = costs["listed"][band].get(slot_role, 0)
        else:
            base = costs["unlisted"][band].get(f"{primary}>{slot_role}", -25)
    flank = 0
    mine = player_side(player)
    if slot_side is not None and mine in ("L", "R") and mine != slot_side:
        flank = WRONG_SIDE_FULLBACK if primary == "FB" else WRONG_SIDE_WINGER
    return base - flank


def positional_yield(role: str, rating: float) -> float:
    edge = 1.0 + (rating - SLOT_MEDIAN_OVR.get(role, 77)) * RATING_EDGE_PER_POINT
    return SLOT_NPXG90.get(role, 0.0) * max(edge, 0.1)


def saturate(raw: float) -> float:
    if raw <= ATTACK_LINEAR_MAX:
        return raw
    head = ATTACK_ASYMPTOTE - ATTACK_LINEAR_MAX
    return ATTACK_LINEAR_MAX + head * (1 - math.exp(-(raw - ATTACK_LINEAR_MAX) / head))


def rate_xi(xi: list[tuple[str, object, int]]) -> tuple[float, float]:
    """(expected goals for, expected goals against) per match, as TeamStrength.rate."""
    attack = 0.0
    num = den = 0.0
    for role, player, pen in xi:
        overall = int(round(player.rating))
        eff = max(1, min(99, overall + pen))
        natural = EA_ROLE.get(player.positions[0], "CM") if player.positions else "CM"
        if role != "GK":
            n = max(0.0, min(1.0, 1 + pen / POSITION_TRANSFER_RANGE))
            # His own output, from the MEASURED rating curve for World Cup players
            # (wc_output.py) rather than the club engine's linear fallback. The
            # out-of-position half of the blend stays the club table, exactly as
            # NationalSide/TeamStrength do it in the app.
            own = wc_output.rate(natural, player.rating)
            attack += n * own + (1 - n) * positional_yield(role, eff)
        w = DEFENSIVE_WEIGHT.get(role, 0.4)
        num += w * eff
        den += w
    gf = max(MIN_XG, min(MAX_XG, ATTACK_SLOPE * saturate(attack) + ATTACK_INTERCEPT))
    ga = max(MIN_XG, min(MAX_XG, math.exp(DEFENCE_LOG_SLOPE * (num / den) + DEFENCE_LOG_INTERCEPT)))
    return gf, ga


def best_xi(players, formations: dict[str, list[tuple[str, str | None]]], costs: dict):
    best = None
    for fid, slots in formations.items():
        value = np.full((11, len(players)), -1e6)
        pens = {}
        for s, (role, side) in enumerate(slots):
            for p, player in enumerate(players):
                pen = penalty(player, role, side, costs)
                if pen is None:
                    continue
                pens[(s, p)] = pen
                value[s, p] = player.rating + pen
        if len(players) < 11:
            continue
        rows, cols = linear_sum_assignment(value, maximize=True)
        if any(value[r, c] <= -1e5 for r, c in zip(rows, cols)):
            continue
        xi = [(slots[r][0], players[c], pens[(r, c)]) for r, c in zip(rows, cols)]
        gf, ga = rate_xi(xi)
        if best is None or gf - ga > best[1] - best[2]:
            best = (fid, gf, ga, xi)
    return best


def measure_strength(results: dict, costs: dict) -> dict:
    section("5. Club-fitted strength model, fed rating-only World Cup squads")
    formations = parse_formations()
    print(f"{len(formations)} formations parsed from Formation.kt")
    matches = results["matches"]

    squads: dict[tuple[int, str], list] = {}
    for ed in wc_fixtures.EDITIONS:
        for team in wc_fixtures.participants(matches, ed):
            pool = wc_squads.pool_for(ed, team)
            squads[(ed, team)] = list(wc_squads.national_squad(pool.players, wc_squads.SQUAD_SIZE[ed]))

    # 5a. A stand-in keeper for nations with none rated. Every real side HAD a
    # keeper; only his rating is missing, because EA did not license his league.
    pts = []
    for sq in squads.values():
        keepers = [p.rating for p in sq if p.line == "GK"]
        outfield = sorted((p.rating for p in sq if p.line != "GK"), reverse=True)[:10]
        if keepers and len(outfield) == 10:
            pts.append((statistics.mean(outfield), max(keepers)))
    x = np.array([a for a, _ in pts])
    y = np.array([b for _, b in pts])
    slope, intercept = np.polyfit(x, y, 1)
    resid = y - (slope * x + intercept)
    print(f"\n5a. best keeper vs mean of best ten outfielders, n={len(pts)} nation-editions:")
    print(f"    keeper = {slope:.4f} * outfield10 + {intercept:.3f}   r = {np.corrcoef(x, y)[0, 1]:+.3f}, "
          f"residual sd {resid.std():.2f}")
    imputed = []
    for key, sq in squads.items():
        outfield = sorted((p.rating for p in sq if p.line != "GK"), reverse=True)[:10]
        if not any(p.line == "GK" for p in sq) and len(outfield) == 10:
            r = slope * statistics.mean(outfield) + intercept
            sq.append(wc_squads.Player("", "(unrated keeper)", int(round(r)), r, ("GK",), ""))
            imputed.append(f"{key[1]} {key[0]} {r:.1f}")
    print("    stand-in keepers: " + ", ".join(imputed))

    ratings: dict[tuple[int, str], tuple[float, float, str]] = {}
    for key, sq in squads.items():
        b = best_xi(sq, formations, costs)
        if b is not None:
            ratings[key] = (b[1], b[2], b[0])
    missing = sorted(set(squads) - set(ratings))
    print(f"\nrated {len(ratings)} entries; still cannot field an XI: {missing}")

    print("\n5b. era check: mean strength of every rated entry, by edition")
    for ed in wc_fixtures.EDITIONS:
        vals = [v for (e, _), v in ratings.items() if e == ed]
        top8 = sorted((gf - ga for gf, ga, _ in vals), reverse=True)[:8]
        print(f"    {ed}: goals for {statistics.mean(v[0] for v in vals):.3f}, against "
              f"{statistics.mean(v[1] for v in vals):.3f}, best eight sides' margin {statistics.mean(top8):+.2f}")
    top = sorted(ratings.items(), key=lambda kv: -(kv[1][0] - kv[1][1]))
    print("    strongest: " + ", ".join(f"{t} {ed} ({gf - ga:+.2f})" for (ed, t), (gf, ga, f) in top[:8]))
    print("    weakest:   " + ", ".join(f"{t} {ed} ({gf - ga:+.2f})" for (ed, t), (gf, ga, f) in top[-5:]))

    def lam(a, b):
        return max(MIN_XG, min(MAX_XG, ratings[a][0] * (ratings[b][1] / LEAGUE_GOALS_PER_TEAM)))

    notes = results["notes"]
    mirror = list(pyreadr.read_r(str(wc_fixtures.MIRROR)).values())[0]
    mirror_notes = {(int(r["Season_End_Year"]), wc_nations.strip_country_code(r["Home"]),
                     wc_nations.strip_country_code(r["Away"])): str(r["Notes"] or "")
                    for r in mirror.to_dict("records")}
    obs = []
    for m in matches:
        h, a = (m.edition, m.home), (m.edition, m.away)
        if h not in ratings or a not in ratings:
            continue
        note = (notes.get((m.edition, m.home, m.away)) if m.edition >= 2022
                else mirror_notes.get((m.edition, m.home, m.away), "")) or ""
        obs.append(dict(lh=lam(h, a), la=lam(a, h), hg=m.home_goals, ag=m.away_goals,
                        ko=m.round != wc_groups.GROUP_ROUND, et="Extra" in note or "penalty" in note,
                        pens="penalty" in note, ed=m.edition, home=m.home, away=m.away,
                        winner=m.shootout_winner))
    group = [o for o in obs if not o["ko"]]
    ko = [o for o in obs if o["ko"]]
    print(f"\n5c. group stage: {len(group)} of 312 matches have both sides rated")

    def nll_map(params, rows):
        a, b = params
        total = 0.0
        for o in rows:
            for lamb, g in ((o["lh"], o["hg"]), (o["la"], o["ag"])):
                mu = math.exp(a) * lamb ** b
                total += mu - g * math.log(mu)
        return total

    full = minimize(nll_map, x0=[0.0, 1.0], args=(group,), method="Nelder-Mead")
    scale_only = minimize_scalar(lambda a: nll_map((a, 1.0), group), bounds=(-2, 2), method="bounded")
    a_hat, b_hat = full.x
    print(f"    raw model: mean predicted goals {statistics.mean(v for o in group for v in (o['lh'], o['la'])):.3f}, "
          f"real {statistics.mean(v for o in group for v in (o['hg'], o['ag'])):.3f}")
    print(f"    fit, scale and shape: real = {math.exp(a_hat):.4f} * predicted^{b_hat:.4f}")
    print(f"    fit, scale only:      real = {math.exp(scale_only.x):.4f} * predicted")
    print(f"    deviance gain from the shape term: {2 * (scale_only.fun - full.fun):.2f} (chi2(1) 5% = 3.84)")

    def mapped(value):
        return math.exp(a_hat) * value ** b_hat

    def outcome_probs(lh, la):
        ph = pd = 0.0
        for i in range(12):
            for j in range(12):
                q = poisson(i, lh) * poisson(j, la)
                if i > j:
                    ph += q
                elif i == j:
                    pd += q
        return ph, pd, 1 - ph - pd

    def logloss(rows, f):
        tot = 0.0
        for o in rows:
            ph, pd, pa = f(o)
            real = o["hg"] - o["ag"]
            tot -= math.log(max(ph if real > 0 else pd if real == 0 else pa, 1e-12))
        return tot / len(rows)

    base_rate = statistics.mean(v for o in group for v in (o["hg"], o["ag"]))
    ll_model = logloss(group, lambda o: outcome_probs(mapped(o["lh"]), mapped(o["la"])))
    ll_flat = logloss(group, lambda o: outcome_probs(base_rate, base_rate))
    print(f"    win/draw/loss log-loss: model {ll_model:.4f}, every side equal {ll_flat:.4f} (lower is better)")
    gd_pred = np.array([mapped(o["lh"]) - mapped(o["la"]) for o in group])
    gd_real = np.array([o["hg"] - o["ag"] for o in group])
    print(f"    goal difference r = {np.corrcoef(gd_pred, gd_real)[0, 1]:+.3f}")
    draw_model = statistics.mean(outcome_probs(mapped(o["lh"]), mapped(o["la"]))[1] for o in group)
    print(f"    draw rate: model {draw_model:.3f}, real {statistics.mean(o['hg'] == o['ag'] for o in group):.3f}")

    section("5d. Knockouts: is a tie decided differently from a group game?")
    decisive = [o for o in ko if not o["et"]]
    print(f"{len(ko)} knockout matches; {len(ko) - len(decisive)} level at 90, {len(decisive)} decided in 90")
    lvl_model = statistics.mean(outcome_probs(mapped(o["lh"]), mapped(o["la"]))[1] for o in ko)
    print(f"    level at 90: real {1 - len(decisive) / len(ko):.3f}, model {lvl_model:.3f} "
          f"(binomial se {math.sqrt(lvl_model * (1 - lvl_model) / len(ko)):.3f})")
    expected_decided = []
    for o in decisive:
        lh, la = mapped(o["lh"]), mapped(o["la"])
        num = den = 0.0
        for i in range(12):
            for j in range(12):
                if i == j:
                    continue
                q = poisson(i, lh) * poisson(j, la)
                num += q * (i + j)
                den += q
        expected_decided.append(num / den)
    print(f"    goals per match in ties decided in 90: real "
          f"{statistics.mean(o['hg'] + o['ag'] for o in decisive):.3f}, "
          f"model given decided {statistics.mean(expected_decided):.3f}")

    def ko_nll(log_c):
        c = math.exp(log_c)
        tot = 0.0
        for o in ko:
            lh, la = mapped(o["lh"]) * c, mapped(o["la"]) * c
            if o["et"]:
                tot -= math.log(max(outcome_probs(lh, la)[1], 1e-12))
            else:
                tot -= math.log(max(poisson(o["hg"], lh) * poisson(o["ag"], la), 1e-300))
        return tot

    kfit = minimize_scalar(ko_nll, bounds=(-1.0, 1.0), method="bounded")
    print(f"    knockout scoring factor MLE {math.exp(kfit.x):.3f}; likelihood ratio vs 1.0: "
          f"{2 * (ko_nll(0.0) - kfit.fun):.2f} (chi2(1) 5% = 3.84)")

    et_rows = [o for o in ko if o["et"]]

    def et_nll(log_k):
        k = math.exp(log_k)
        tot = 0.0
        for o in et_rows:
            lh, la = mapped(o["lh"]) * k / 3, mapped(o["la"]) * k / 3
            pl = outcome_probs(lh, la)[1]
            tot -= math.log(max(pl if o["pens"] else 1 - pl, 1e-12))
        return tot

    efit = minimize_scalar(et_nll, bounds=(-3, 2), method="bounded")
    grid = np.linspace(0.1, 2.0, 191)
    ll = np.array([-et_nll(math.log(g)) for g in grid])
    inside = grid[ll >= ll.max() - 1.92]
    print(f"    extra-time scoring factor (per minute, relative to ninety): {math.exp(efit.x):.3f} "
          f"(95% interval {inside.min():.2f}-{inside.max():.2f}) over {len(et_rows)} ties, "
          f"{sum(o['pens'] for o in et_rows)} to penalties")

    section("6. Host advantage, and who wins shootouts")
    hosts = {2006: {"Germany"}, 2010: {"South Africa"}, 2014: {"Brazil"}, 2018: {"Russia"},
             2022: {"Qatar"}, 2026: {"USA", "Canada", "Mexico"}}
    host_rows = []
    for o in obs:
        if o["et"]:
            continue
        for team, lf, la, gf, ga in ((o["home"], o["lh"], o["la"], o["hg"], o["ag"]),
                                     (o["away"], o["la"], o["lh"], o["ag"], o["hg"])):
            if team in hosts[o["ed"]]:
                host_rows.append((mapped(lf), mapped(la), gf, ga))

    def host_nll(params):
        hf_, ha_ = math.exp(params[0]), math.exp(params[1])
        return sum(lf * hf_ - gf * math.log(lf * hf_) + la * ha_ - ga * math.log(la * ha_)
                   for lf, la, gf, ga in host_rows)

    hfit = minimize(host_nll, x0=[0.0, 0.0], method="Nelder-Mead")
    hf, ha = (math.exp(v) for v in hfit.x)
    lr_f = 2 * (host_nll([0.0, hfit.x[1]]) - hfit.fun)
    lr_a = 2 * (host_nll([hfit.x[0], 0.0]) - hfit.fun)
    print(f"host sides, {len(host_rows)} matches decided in 90: scoring x{hf:.3f} (LR {lr_f:.2f}), "
          f"conceding x{ha:.3f} (LR {lr_a:.2f}); chi2(1) 5% = 3.84")
    print("    club home advantage for comparison: x1.2452 scoring")

    shoot = [o for o in obs if o["pens"]]
    stronger_won = sum(o["winner"] == (o["home"] if o["lh"] > o["la"] else o["away"]) for o in shoot)
    print(f"shootouts 2006-2026: {len(shoot)}; the model's stronger side won {stronger_won} "
          f"(two-sided binomial p = {binom_two_sided(stronger_won, len(shoot)):.2f})")
    return {"keeper": (slope, intercept), "map": (math.exp(a_hat), b_hat)}


def binom_two_sided(k: int, n: int) -> float:
    from math import comb
    probs = [comb(n, i) / 2 ** n for i in range(n + 1)]
    return min(1.0, sum(p for p in probs if p <= probs[k] + 1e-12))


def poisson(k: int, lam: float) -> float:
    return math.exp(-lam) * lam ** k / math.factorial(k)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    results = measure_results()
    costs = measure_position_costs()
    measure_strength(results, costs)


if __name__ == "__main__":
    main()
