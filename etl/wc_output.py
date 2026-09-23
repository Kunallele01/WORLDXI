"""
What a player of a given rating actually produces, per position — measured, and
generated into the app as WcOutput.kt.

WHY THIS EXISTS. A World Cup squad carries ratings and positions and nothing
else, so every player's goal threat has to be estimated from his rating. That
estimate used the club engine's missing-data fallback: a position's median
output scaled LINEARLY by 3% per rating point. Checked against the real club
seasons, that rule is wrong where this mode lives:

    striker 70-79   real 0.260 npxG/90   estimate 0.305   (+17%)
    striker 88+     real 0.659           estimate 0.507   (-23%)
    winger  70-79   real 0.136           estimate 0.173   (+27%)
    winger  88+     real 0.572           estimate 0.283   (-51%)

It flatters weak attackers and halves elite ones, so a 92-rated XI was rated
1.71 expected goals a match against England 2018's real 1.81 — the model could
not tell the best eleven ever assembled from a good international side, which
is exactly what the user reported.

Output grows MULTIPLICATIVELY with rating, not linearly, so this fits

    rate(rating) = base * exp(slope * (rating - the role's median rating))

per role, by weighted least squares on per-band means (weights = players in the
band). Three rates are fitted: non-penalty expected goals, which decides how
many goals a side scores, and goals and assists per 90, which decide who gets
them.

CLUB MODE IS NOT TOUCHED. Club players carry their real per-season xG and only
fall back to the club estimator for the ~5% missing it; this curve is used by
NationalSide.simPlayer alone, on players who have no stats at all.
"""
from __future__ import annotations

import csv
import glob
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
STAGED = HERE / "staged"
KOTLIN = HERE.parent / "app/src/main/java/com/dreamxi/app/sim/worldcup/WcOutput.kt"

#: Minutes before a season describes a player rather than a cameo — the floor
#: the club ETL and draft already use for a "regular".
MIN_MINUTES = 900
#: Rating band width, and the players a band needs before it is fitted.
BAND = 2
MIN_BAND = 8
ROLES = ["GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST"]
#: The club engine's median rating per role — the anchor the curve is measured from.
SLOT_MEDIAN_OVR = {"GK": 79, "CB": 77, "FB": 77, "DM": 77, "CM": 77, "CAM": 80, "Winger": 78, "ST": 79}
#: What the club engine's linear rule says, for the comparison printed below.
CLUB_NPXG90 = {"GK": 0.000, "CB": 0.047, "FB": 0.034, "DM": 0.040,
               "CM": 0.073, "CAM": 0.161, "Winger": 0.193, "ST": 0.353}


def load() -> dict[str, list[tuple[int, float, float, float]]]:
    """role -> (rating, npxg/90, goals/90, assists/90) for every regular season."""
    out: dict[str, list[tuple[int, float, float, float]]] = defaultdict(list)
    for path in glob.glob(str(STAGED / "playerseasons_*.tsv")):
        with open(path, encoding="utf-8", newline="") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                role = r["primary_position"]
                try:
                    minutes = int(r["minutes"] or 0)
                    rating = int(r["overall_rating"] or 0)
                except ValueError:
                    continue
                if role not in ROLES or minutes < MIN_MINUTES or not rating:
                    continue

                def per90(key: str) -> float:
                    try:
                        return float(r[key] or 0) * 90 / minutes
                    except ValueError:
                        return 0.0

                out[role].append((rating, per90("npxg"), per90("goals"), per90("assists")))
    return out


def fit(points: list[tuple[int, float]], anchor: int) -> tuple[float, float, float, int, int]:
    """(base at the anchor, slope per point, r^2, players, highest rating measured).

    The last number is where the evidence stops. A curve fitted on defensive
    midfielders rated 72-86 says a 92-rated one produces three times the median;
    that is extrapolation, not measurement, so the app holds the curve flat
    above the highest band this fit actually saw.
    """
    bands: dict[int, list[float]] = defaultdict(list)
    for rating, value in points:
        bands[rating - rating % BAND].append(value)
    xs, ys, weights = [], [], []
    for floor, values in sorted(bands.items()):
        mean = statistics.mean(values)
        if len(values) < MIN_BAND or mean <= 0:
            continue
        xs.append(floor + BAND / 2 - anchor)
        ys.append(math.log(mean))
        weights.append(len(values))
    if len(xs) < 3:
        return 0.0, 0.0, 0.0, len(points), anchor
    x, y, w = np.array(xs), np.array(ys), np.array(weights, dtype=float)
    slope, intercept = np.polyfit(x, y, 1, w=np.sqrt(w))
    predicted = slope * x + intercept
    ss_res = float((w * (y - predicted) ** 2).sum())
    ss_tot = float((w * (y - np.average(y, weights=w)) ** 2).sum())
    top = int(anchor + max(xs))
    return math.exp(intercept), slope, 1 - ss_res / ss_tot if ss_tot else 0.0, len(points), top


_CURVES: dict[str, dict[str, tuple[float, float, int, int]]] | None = None


def curves() -> dict[str, dict[str, tuple[float, float, int, int]]]:
    """role -> stat -> (base, slope, anchor, top), the same fit the app ships."""
    global _CURVES
    if _CURVES is None:
        data = load()
        _CURVES = {}
        for role in ROLES:
            points = data.get(role, [])
            _CURVES[role] = {}
            for i, stat in enumerate(("npxg", "goals", "assists"), start=1):
                base, slope, _, _, top = fit([(pt[0], pt[i]) for pt in points], SLOT_MEDIAN_OVR[role])
                _CURVES[role][stat] = (base, slope, SLOT_MEDIAN_OVR[role], top)
    return _CURVES


def rate(role: str, rating: float, stat: str = "npxg") -> float:
    """What a World Cup player of this rating produces here. Mirrors WcOutput.kt."""
    curve = curves().get(role, {}).get(stat)
    if curve is None:
        return 0.0
    base, slope, anchor, top = curve
    return base * math.exp(slope * (min(rating, top) - anchor))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    data = load()
    fits: dict[str, dict[str, tuple[float, float, float, int]]] = {}
    print(f"{'role':8s} {'stat':8s} {'n':>6s} {'base at median':>15s} {'per point':>10s} {'r2':>6s}"
          f"   rate at 92 vs the old linear rule")
    for role in ROLES:
        points = data.get(role, [])
        fits[role] = {}
        for i, stat in enumerate(("npxg", "goals", "assists"), start=1):
            base, slope, r2, n, top = fit([(p[0], p[i]) for p in points], SLOT_MEDIAN_OVR[role])
            fits[role][stat] = (base, slope, r2, n, top)
            if stat == "npxg":
                mine = base * math.exp(slope * (min(92, top) - SLOT_MEDIAN_OVR[role]))
                theirs = CLUB_NPXG90[role] * max(0.1, 1 + (92 - SLOT_MEDIAN_OVR[role]) * 0.03)
                extra = f"   {mine:.3f} vs {theirs:.3f}  (x{mine / theirs:.2f})" if theirs else ""
            else:
                extra = ""
            print(f"{role:8s} {stat:8s} {n:6d} {base:15.4f} {slope:+10.4f} {r2:6.2f}  measured to {top}{extra}")

    lines = [
        "package com.dreamxi.app.sim.worldcup",
        "",
        "import kotlin.math.exp",
        "",
        "// GENERATED by etl/wc_output.py from the real club seasons. Do not edit by hand.",
        "",
        "/**",
        " * What a World Cup player of a given rating produces, per position.",
        " *",
        " * A World Cup squad has ratings and positions and no stats, so output has to",
        " * be estimated from rating. It grows MULTIPLICATIVELY:",
        " *",
        " *     rate = base * exp(slope * (rating - the role's median rating))",
        " *",
        " * fitted per role on per-band means of real club seasons (900+ minutes).",
        " * The club engine's linear 3%-per-point fallback, which this replaces HERE",
        " * AND ONLY HERE, flattered weak attackers and halved elite ones: it put a",
        " * 92-rated winger at 0.28 npxG/90 against a measured 0.57, so a side of 92s",
        " * rated no better in attack than a real England XI.",
        " *",
        " * CLUB MODE IS UNCHANGED. Club players have their own per-season xG; this is",
        " * used by NationalSide.simPlayer alone.",
        " */",
        "internal object WcOutput {",
        "",
        "    /** [top] is the highest rating the fit saw: above it the curve holds flat. */",
        "    private data class Curve(val base: Double, val slope: Double, val anchor: Int, val top: Int)",
        "",
    ]
    for stat, name in (("npxg", "NPXG90"), ("goals", "GOALS90"), ("assists", "ASSISTS90")):
        lines.append(f"    private val {name}: Map<String, Curve> = mapOf(")
        for role in ROLES:
            base, slope, r2, n, top = fits[role][stat]
            lines.append(f'        "{role}" to Curve({base:.5f}, {slope:.5f}, {SLOT_MEDIAN_OVR[role]}, {top}),'
                         f"  // n={n}, r2={r2:.2f}, measured to {top}")
        lines.append("    )")
        lines.append("")
    lines += [
        "    private fun rate(curves: Map<String, Curve>, role: String, rating: Double): Double {",
        "        val curve = curves[role] ?: return 0.0",
        "        // Held flat above the highest rating the fit saw: beyond that the curve",
        "        // would be extrapolating, and a 92-rated defensive midfielder is exactly",
        "        // where that goes wrong.",
        "        val measured = rating.coerceAtMost(curve.top.toDouble())",
        "        return curve.base * exp(curve.slope * (measured - curve.anchor))",
        "    }",
        "",
        "    /** Non-penalty expected goals per 90 — what the side's scoring is built from. */",
        "    fun npxg90(role: String, rating: Double): Double = rate(NPXG90, role, rating)",
        "",
        "    /** Goals per 90, penalties included: who gets credited once a goal is scored. */",
        "    fun goals90(role: String, rating: Double): Double = rate(GOALS90, role, rating)",
        "",
        "    /** Assists per 90, for the same job. */",
        "    fun assists90(role: String, rating: Double): Double = rate(ASSISTS90, role, rating)",
        "}",
        "",
    ]
    KOTLIN.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nwrote {KOTLIN.name}")


if __name__ == "__main__":
    main()
