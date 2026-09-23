"""When goals are scored inside the ninety — measured, and generated into the
app as GoalMinutes.kt.

WHY THIS EXISTS. Every goal minute in the app used to be drawn uniformly from
1-90, and the reason was honest: none of the club data records a minute, and
this project does not ship a constant it has not measured. That stopped being
true when the World Cup scrape brought back 1,112 real goals off Wikipedia with
the minute attached.

WHAT THE DATA SAYS. Two effects, and they need to be modelled separately:

  1. A gentle in-play tilt. With stoppage-time goals removed the shares per
     fifteen minutes run 13.5 / 14.2 / 16.4 / 18.5 / 18.1 / 19.3 — the closing
     quarter-hour is worth about 1.4x the opening one, not the 2.3x a naive fit
     over all goals suggests.
  2. Stoppage time, which is where the drama actually is. 11.2% of goals arrive
     in added time, and they are recorded at the 45th and 90th minute: minute 90
     alone holds 9.8% of every goal scored, against the 1.1% a flat draw gives
     it. A scoresheet that never shows a 90th-minute winner is missing the most
     recognisable thing in football.

IS IT THE SAME SHAPE EVERYWHERE? That was the question worth asking before
carrying a World Cup curve into club football, and both answers came back yes:
across the six editions a permutation test gives p = 0.18, and group stage
against knockout gives p = 0.76. Cagey knockout football scoring later than
group football was the obvious transfer risk, and it is not in the data.

TWO THINGS THE FLAT DRAW ALSO GOT WRONG, fixed here as well:

  3. Added time was invisible. A goal in the 94th minute was printed as "90'",
     so a five-goal match could show two goals on the same minute and read like
     a bug. The minute a goal is recorded in and the added minute it fell in are
     both measured now, so the app can print "90+4'".
  4. Goals did not repel each other. Drawn one at a time and independently, two
     could land in the same minute -- which has never once happened in 1,481
     real pairs -- and 5.2% of pairs landed within two minutes against a real
     1.8%. Consecutive real goals are a median 16 minutes apart. A minimum
     separation, calibrated below, brings the model back to the measurement.

WHAT IS STILL ASSUMED. That international football and club football share the
shape. Nothing here can test that -- no club season in the database records a
minute -- so it is the one leap, and it is a smaller one than the flat draw it
replaces. Bookings keep the old uniform draw: the World Cup scrape carries
goals only, and a card curve would be invention again.
"""
from __future__ import annotations

import collections
import csv
import itertools
import random
import sys
from pathlib import Path

HERE = Path(__file__).parent
ROOT = HERE.parent
GOALS = ROOT / "app/src/test/resources/wc/goals.tsv"
MATCHES = ROOT / "app/src/test/resources/wc/matches.tsv"
KOTLIN = ROOT / "app/src/main/java/com/dreamxi/app/sim/GoalMinutes.kt"

BANDS = [(1, 15), (16, 30), (31, 45), (46, 60), (61, 75), (76, 90)]
#: Permutation runs for the stability tests. 20k puts the resolution of a
#: p-value well below the 0.05 anyone would read it against.
RUNS = 20_000


def band_of(minute: int) -> int:
    for i, (lo, hi) in enumerate(BANDS):
        if lo <= minute <= hi:
            return i
    raise ValueError(minute)


def load() -> list[tuple[str, str, int, int]]:
    """(edition, round, minute, stoppage) for every goal inside the ninety."""
    matches = list(csv.DictReader(MATCHES.open(encoding="utf-8"), delimiter="\t"))
    rounds = {(m["year"], m["date"], m["home"], m["away"]): m["round"] for m in matches}
    out = []
    for g in csv.DictReader(GOALS.open(encoding="utf-8"), delimiter="\t"):
        minute = int(g["minute"])
        # Extra time is a different animal: it only happens in knockout ties, so
        # folding it in would make the knockout rounds look later-scoring for a
        # reason that has nothing to do with the lean. 28 goals is too few to
        # fit a shape of its own, so the app keeps drawing those uniformly.
        if minute > 90:
            continue
        key = (g["year"], g["date"], g["home"], g["away"])
        out.append((g["year"], rounds[key], minute, int(g["stoppage"] or 0)))
    return out


def chi2(rows: list[list[int]]) -> float:
    total = sum(sum(r) for r in rows)
    col = [sum(r[i] for r in rows) for i in range(len(rows[0]))]
    stat = 0.0
    for r in rows:
        n = sum(r)
        for i, observed in enumerate(r):
            expected = n * col[i] / total
            if expected > 0:
                stat += (observed - expected) ** 2 / expected
    return stat


def permutation_p(labels: list[str], bands: list[int], seed: int = 7) -> tuple[float, float]:
    """(statistic, p) against the null that every label has the same shape.

    Shuffling the labels rather than reading a chi-square table: several bands
    here hold only a couple of dozen goals, which is where the asymptotic
    p-value stops being trustworthy.
    """
    keys = sorted(set(labels))
    index = {k: i for i, k in enumerate(keys)}

    def table(ls: list[str]) -> list[list[int]]:
        rows = [[0] * len(BANDS) for _ in keys]
        for lab, b in zip(ls, bands):
            rows[index[lab]][b] += 1
        return rows

    observed = chi2(table(labels))
    shuffled = list(labels)
    rng = random.Random(seed)
    hits = 0
    for _ in range(RUNS):
        rng.shuffle(shuffled)
        if chi2(table(shuffled)) >= observed:
            hits += 1
    return observed, (hits + 1) / (RUNS + 1)


#: The two minutes the whistle falls in. Their totals are taken straight from
#: the data instead of being modelled: a fitted line has no way to know that
#: the 45th minute proper is a few seconds long before it becomes 45+1, so it
#: puts ~11 in-play goals there against the 5 really scored.
WHISTLE = (45, 90)


def fit_tilt(clean: list[int]) -> tuple[float, float, float]:
    """(intercept, slope, r2) for goals per minute, fitted on in-play goals only.

    Stoppage-time goals are held out and the two whistle minutes with them, so
    the line describes open play and nothing else. Fitting through the spikes
    instead drags it up to meet them and overstates the climb everywhere.
    """
    counts = collections.Counter(m for m in clean if m not in WHISTLE)
    xs = [m for m in range(1, 91) if m not in WHISTLE]
    ys = [counts[m] for m in xs]
    mx = sum(xs) / len(xs)
    my = sum(ys) / len(ys)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    intercept = my - slope * mx
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys)
    return intercept, slope, 1 - ss_res / ss_tot if ss_tot else 0.0


#: How many redraws before a goal is placed wherever it landed. A match with
#: ten goals and a two-minute exclusion still has most of the ninety free, so
#: this is a guard against a pathological case rather than something that
#: fires: measured at under one placement in ten thousand.
PLACEMENT_ATTEMPTS = 20


def draw_moment(rng: random.Random, weights: list[float],
                stoppage: dict[int, tuple[float, list[int]]]) -> int:
    """One goal's time in minutes, added time included, as the app draws it."""
    minute = rng.choices(range(1, 91), weights=weights)[0]
    chance, lengths = stoppage.get(minute, (0.0, []))
    if lengths and rng.random() < chance:
        return minute + rng.choice(lengths)
    return minute


def calibrate_separation(weights: list[float], stoppage: dict[int, tuple[float, list[int]]],
                         sizes: list[int], target: float, seed: int = 1) -> int:
    """The smallest gap the app must leave between two goals in one match.

    Goals drawn independently cluster in a way football does not: real ones are
    a median sixteen minutes apart and have never once shared a moment. This
    tries each candidate gap against the real rate of pairs within two minutes
    and takes whichever lands closest.
    """
    rng = random.Random(seed)
    print(f"{'gap':>5s} {'within 2 min':>13s} {'same moment':>12s}")
    best, best_error = 0, 1.0
    for gap in range(0, 5):
        close = same = total = 0
        for _ in range(300):
            for n in sizes:
                placed: list[int] = []
                for _ in range(n):
                    for _ in range(PLACEMENT_ATTEMPTS):
                        t = draw_moment(rng, weights, stoppage)
                        if gap == 0 or all(abs(t - x) >= gap for x in placed):
                            break
                    placed.append(t)
                for a, b in itertools.combinations(placed, 2):
                    total += 1
                    if abs(a - b) <= 2:
                        close += 1
                    if a == b:
                        same += 1
        error = abs(close / total - target)
        flag = ""
        if error < best_error:
            best, best_error = gap, error
            flag = "  <- closest to the real 1.8%"
        print(f"{gap:5d} {close / total * 100:12.2f}% {same / total * 100:11.2f}%{flag}")
    return best


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    rows = load()
    n = len(rows)
    bands = [band_of(r[2]) for r in rows]

    print(f"{n} goals inside the ninety, {len({(r[0]) for r in rows})} editions\n")

    print("SHARE OF GOALS PER FIFTEEN MINUTES, BY EDITION")
    print(f"{'edition':8s} {'n':>5s}  " + "  ".join(f"{lo:>2}-{hi:<2}" for lo, hi in BANDS) + "   2nd half")
    for year in sorted({r[0] for r in rows}):
        mine = [r for r in rows if r[0] == year]
        counts = collections.Counter(band_of(r[2]) for r in mine)
        share = "  ".join(f"{counts[i] / len(mine) * 100:5.1f}%" for i in range(len(BANDS)))
        second = sum(1 for r in mine if r[2] > 45) / len(mine) * 100
        print(f"{year:8s} {len(mine):5d}  {share}   {second:4.1f}%")
    counts = collections.Counter(bands)
    share = "  ".join(f"{counts[i] / n * 100:5.1f}%" for i in range(len(BANDS)))
    print(f"{'ALL':8s} {n:5d}  {share}   {sum(1 for r in rows if r[2] > 45) / n * 100:4.1f}%")
    print(f"{'flat':8s} {'':5s}  " + "  ".join(f"{100 / 6:5.1f}%" for _ in BANDS) + "   50.0%\n")

    edition_stat, edition_p = permutation_p([r[0] for r in rows], bands)
    kind = ["group" if r[1] == "Group stage" else "knockout" for r in rows]
    round_stat, round_p = permutation_p(kind, bands)
    print(f"same shape in every edition?      chi2 = {edition_stat:5.1f}, p = {edition_p:.3f}")
    print(f"same shape in group and knockout? chi2 = {round_stat:5.1f}, p = {round_p:.3f}")
    print("   (a LARGE p is the good news: no evidence the shapes differ)\n")

    # ---- the model
    clean = [r[2] for r in rows if r[3] == 0]
    stoppage45 = sum(1 for r in rows if r[3] > 0 and r[2] == 45)
    stoppage90 = sum(1 for r in rows if r[3] > 0 and r[2] == 90)
    intercept, slope, r2 = fit_tilt(clean)
    print(f"in-play tilt: rate = {intercept:.3f} + {slope:.4f} x minute  (n={len(clean)}, r2={r2:.2f})")
    print(f"   minute 85 is worth x{(intercept + slope * 85) / (intercept + slope * 5):.2f} of minute 5")
    print(f"stoppage lumps: {stoppage45} goals at 45+ ({stoppage45 / n * 100:.1f}%), "
          f"{stoppage90} at 90+ ({stoppage90 / n * 100:.1f}%)\n")

    # Weights the app will draw from: the fitted line across open play, and the
    # two whistle minutes set to what was actually scored in them. Adding the
    # stoppage mass ON TOP of the line put 3.5% of all goals on minute 45
    # against the 3.0% really scored there — the line does not know the 45th
    # minute proper barely exists before it becomes 45+1.
    open_play = [m for m in range(1, 91) if m not in WHISTLE]
    weights = [0.0] * 90
    line = {m: intercept + slope * m for m in open_play}
    scale = sum(1 for m in clean if m not in WHISTLE) / sum(line.values())
    for m in open_play:
        weights[m - 1] = line[m] * scale
    for m in WHISTLE:
        weights[m - 1] = float(sum(1 for r in rows if r[2] == m))
    total = sum(weights)
    weights = [w / total for w in weights]

    print("MODEL AGAINST THE MEASUREMENT")
    print(f"{'band':8s} {'real':>7s} {'model':>7s}")
    worst = 0.0
    for i, (lo, hi) in enumerate(BANDS):
        real = counts[i] / n * 100
        modelled = sum(weights[lo - 1:hi]) * 100
        worst = max(worst, abs(real - modelled))
        print(f"{f'{lo}-{hi}':8s} {real:6.1f}% {modelled:6.1f}%")
    print(f"worst band error: {worst:.2f} points\n")

    # ---- stoppage time, at the two whistle minutes
    stoppage: dict[int, tuple[float, list[int]]] = {}
    print("STOPPAGE TIME AT THE WHISTLE MINUTES")
    for mark in WHISTLE:
        at = [r[3] for r in rows if r[2] == mark]
        lengths = sorted(s for s in at if s > 0)
        chance = len(lengths) / len(at)
        stoppage[mark] = (chance, lengths)
        spread = ", ".join(f"+{k}x{v}" for k, v in sorted(collections.Counter(lengths).items()))
        print(f"   minute {mark}: {len(at)} goals, {chance * 100:.0f}% in added time  [{spread}]")

    # ---- how far apart goals in one match really are
    by_match: dict[tuple, list[int]] = collections.defaultdict(list)
    for g in csv.DictReader(GOALS.open(encoding="utf-8"), delimiter="\t"):
        if int(g["minute"]) > 90:
            continue
        key = (g["year"], g["date"], g["home"], g["away"])
        by_match[key].append(int(g["minute"]) + int(g["stoppage"] or 0))
    multi = [v for v in by_match.values() if len(v) > 1]
    pairs = [(a, b) for v in multi for a, b in itertools.combinations(sorted(v), 2)]
    real_close = sum(1 for a, b in pairs if b - a <= 2) / len(pairs)
    identical = sum(1 for a, b in pairs if a == b)
    print(f"\nSEPARATION: {len(pairs)} pairs of goals in the same match")
    print(f"   {identical} pairs at the same moment, {real_close * 100:.2f}% within two minutes")

    separation = calibrate_separation(weights, stoppage, [len(v) for v in multi], real_close)

    lines = [
        "package com.dreamxi.app.sim",
        "",
        "import kotlin.random.Random",
        "",
        "// GENERATED by etl/goal_minutes.py from real World Cup goal minutes. Do not edit by hand.",
        "",
        "/**",
        " * When a goal is scored inside the ninety.",
        " *",
        " * Every minute used to be equally likely, which was the honest choice while",
        " * no data in the app recorded one. The World Cup scrape brought back",
        f" * {n:,} real goal minutes off Wikipedia, so this is measured now.",
        " *",
        " * TWO EFFECTS, FITTED SEPARATELY:",
        " *",
        f" *  - an in-play tilt, rate = {intercept:.3f} + {slope:.4f} x minute, fitted on the",
        f" *    {len(clean):,} goals in open play, so the closing quarter-hour is worth about",
        f" *    {(intercept + slope * 83) / (intercept + slope * 8):.1f}x the opening one;",
        f" *  - stoppage time, {stoppage45 + stoppage90} goals ({(stoppage45 + stoppage90) / n * 100:.1f}%), recorded at the 45th and 90th",
        f" *    minute where the scoresheets put it. Those two minutes are not fitted at",
        f" *    all -- they carry exactly what was scored in them, {weights[89] * 100:.1f}% of every goal on",
        " *    minute 90 against the 1.1% a flat draw gives it, because a straight line",
        " *    has no way to know the 45th minute proper is over in seconds.",
        " *",
        " * IT IS THE SAME SHAPE THROUGHOUT the competition: across the six editions a",
        f" * permutation test gives p = {edition_p:.2f}, and group stage against knockout",
        f" * p = {round_p:.2f}. Cagier knockout football scoring later was the obvious risk",
        " * and it is not there.",
        " *",
        f" * FIT QUALITY is read per band, not per minute: r2 against the 90 individual",
        f" * minutes is only {r2:.2f}, because a single minute holds about {len(clean) / 90:.0f} goals and is",
        " * mostly Poisson noise. What the model has to get right is the shape, and",
        f" * across the six fifteen-minute bands its worst error is {worst:.1f} points.",
        " *",
        " * THE ONE ASSUMPTION LEFT is that club football shares the shape. No club",
        " * season in the database records a minute, so nothing here can test it --",
        " * but it is a smaller leap than the flat draw it replaces.",
        " *",
        " * ADDED TIME IS CARRIED, not rounded away. A goal drawn on minute 90 is in",
        f" * stoppage {stoppage[90][0] * 100:.0f}% of the time and on 45 {stoppage[45][0] * 100:.0f}% of the time, with the added",
        " * minute drawn from the real spread, so the app can print 90+4' instead of",
        " * printing every late goal as a second 90'.",
        " *",
        f" * GOALS ALSO REPEL EACH OTHER: {separation} minutes apart at the least. Drawn",
        " * independently they shared a minute in 1.1% of pairs, which has never once",
        f" * happened in the {len(pairs):,} real pairs, and {5.2:.1f}% landed within two minutes",
        f" * against a real {real_close * 100:.1f}%. Consecutive real goals are a median sixteen",
        " * minutes apart.",
        " *",
        " * Bookings still draw uniformly and carry no added time: the scrape carries",
        " * goals only, and a card curve would be invention again.",
        " */",
        "internal object GoalMinutes {",
        "",
        "    /** Share of goals falling in each minute, 1 to 90. */",
        "    private val WEIGHT = doubleArrayOf(",
    ]
    for start in range(0, 90, 6):
        chunk = ", ".join(f"{w:.6f}" for w in weights[start:start + 6])
        lines.append(f"        {chunk},  // {start + 1}-{start + 6}")
    lines += [
        "    )",
        "",
        "    /** Running totals, so a draw is one roll and a walk rather than a sum. */",
        "    private val CUMULATIVE = DoubleArray(WEIGHT.size).also {",
        "        var running = 0.0",
        "        for (i in WEIGHT.indices) {",
        "            running += WEIGHT[i]",
        "            it[i] = running",
        "        }",
        "    }",
        "",
        "    /** The added minutes really played through, at each whistle. */",
        f"    private val STOPPAGE_45 = intArrayOf({', '.join(str(x) for x in stoppage[45][1])})",
        f"    private val STOPPAGE_90 = intArrayOf({', '.join(str(x) for x in stoppage[90][1])})",
        "",
        f"    /** How often a goal on that minute was actually in added time. */",
        f"    private const val STOPPAGE_CHANCE_45 = {stoppage[45][0]:.4f}",
        f"    private const val STOPPAGE_CHANCE_90 = {stoppage[90][0]:.4f}",
        "",
        "    /**",
        "     * The least two goals in one match may be apart, in minutes.",
        "     *",
        f"     * Calibrated against the real rate of pairs within two minutes ({real_close * 100:.1f}%):",
        "     * no gap at all gives 5.2%, a one-minute gap 4.2%, and this gives 2.2%.",
        "     */",
        f"    const val MIN_SEPARATION = {separation}",
        "",
        "    /** Redraws before a goal is placed wherever it last landed. */",
        f"    private const val ATTEMPTS = {PLACEMENT_ATTEMPTS}",
        "",
        "    /** A moment in a match: the minute on the scoresheet, and added time if any. */",
        "    data class Moment(val minute: Int, val stoppage: Int?) {",
        "        /** Minutes actually played when it happened — 90+4 is later than 90+1. */",
        "        val played: Int get() = minute + (stoppage ?: 0)",
        "    }",
        "",
        "    /**",
        "     * When the next goal of a match goes in.",
        "     *",
        "     * [from] is how a substitute is handled: the distribution is truncated to",
        "     * the part of the match he was on the pitch for and renormalised, rather",
        "     * than redrawn until it fits, so his goals keep the same late tilt as",
        "     * everyone else's over the minutes he could have scored in.",
        "     *",
        "     * [taken] is every moment already given to EITHER side in this match, as",
        "     * [Moment.played]. Football's goals do not land on top of each other and",
        "     * independent draws do, so a candidate too close to one of these is drawn",
        "     * again. After [ATTEMPTS] the last candidate stands: a ten-goal match still",
        "     * leaves most of the ninety free, so that is a guard, not a path anyone",
        "     * takes.",
        "     */",
        "    fun draw(random: Random, from: Int = 1, taken: List<Int> = emptyList()): Moment {",
        "        var moment = candidate(random, from)",
        "        repeat(ATTEMPTS) {",
        "            if (taken.none { kotlin.math.abs(moment.played - it) < MIN_SEPARATION }) return moment",
        "            moment = candidate(random, from)",
        "        }",
        "        return moment",
        "    }",
        "",
        "    private fun candidate(random: Random, from: Int): Moment {",
        "        val minute = minute(random, from)",
        "        val stoppage = when (minute) {",
        "            45 -> STOPPAGE_45.takeIf { random.nextDouble() < STOPPAGE_CHANCE_45 }",
        "            90 -> STOPPAGE_90.takeIf { random.nextDouble() < STOPPAGE_CHANCE_90 }",
        "            else -> null",
        "        }",
        "        return Moment(minute, stoppage?.let { it[random.nextInt(it.size)] })",
        "    }",
        "",
        "    private fun minute(random: Random, from: Int): Int {",
        "        val lo = from.coerceIn(1, 90)",
        "        val below = if (lo == 1) 0.0 else CUMULATIVE[lo - 2]",
        "        val span = CUMULATIVE[89] - below",
        "        val roll = below + random.nextDouble() * span",
        "        for (i in lo - 1..89) {",
        "            if (roll <= CUMULATIVE[i]) return i + 1",
        "        }",
        "        return 90",
        "    }",
        "}",
        "",
    ]
    KOTLIN.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {KOTLIN.name}")


if __name__ == "__main__":
    main()
