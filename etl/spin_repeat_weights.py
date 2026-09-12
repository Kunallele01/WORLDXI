"""
How often does a club repeat in a draft, and what do repeat weights do to it?

The pool is 20 clubs x 5 seasons = 100 club-seasons, and a draft takes 11 spins.
Uniform sampling therefore repeats clubs quite often, which reads as broken even
though it is fair. Hard exclusion fixes that and goes too far: it makes a club
appearing twice IMPOSSIBLE rather than uncommon.

So: weight a club-season down each time its club has already come up, and weight
the exact same club-season down much further. This measures what each setting
actually produces so the numbers are chosen from evidence rather than taste.
"""
from __future__ import annotations
import random
from collections import Counter

CLUBS, SEASONS, ROUNDS, TRIALS = 20, 5, 11, 200_000
POOL = [(c, s) for c in range(CLUBS) for s in range(SEASONS)]


def draft(club_w: float, exact_w: float, rng: random.Random) -> Counter:
    club_hits: Counter = Counter()
    exact_hits: Counter = Counter()
    drawn = []
    for _ in range(ROUNDS):
        weights = [
            (club_w ** club_hits[c]) * (exact_w ** exact_hits[(c, s)])
            for (c, s) in POOL
        ]
        pick = rng.choices(POOL, weights=weights, k=1)[0]
        drawn.append(pick)
        club_hits[pick[0]] += 1
        exact_hits[pick] += 1
    return Counter(c for c, _ in drawn), drawn


def measure(club_w: float, exact_w: float, seed: int = 1) -> dict:
    rng = random.Random(seed)
    twice = thrice = exact_repeat = 0
    distinct_total = 0
    for _ in range(TRIALS):
        counts, drawn = draft(club_w, exact_w, rng)
        top = max(counts.values())
        if top >= 2: twice += 1
        if top >= 3: thrice += 1
        if len(set(drawn)) < ROUNDS: exact_repeat += 1
        distinct_total += len(counts)
    return {
        "a club twice+": twice / TRIALS * 100,
        "a club 3+ times": thrice / TRIALS * 100,
        "same club-season twice": exact_repeat / TRIALS * 100,
        "distinct clubs": distinct_total / TRIALS,
    }


print(f"{'club w':>7} {'exact w':>8} {'club 2+':>9} {'club 3+':>9} "
      f"{'same c-s twice':>15} {'distinct clubs':>15}")
settings = [
    (1.00, 1.00),   # what shipped originally: uniform, no memory
    (0.50, 0.10),
    (0.35, 0.05),
    (0.25, 0.05),
    (0.15, 0.02),
    (0.00, 0.00),   # the hard exclusion I wrongly shipped
]
for club_w, exact_w in settings:
    m = measure(club_w, exact_w)
    label = f"{club_w:>7.2f} {exact_w:>8.2f}"
    print(f"{label} {m['a club twice+']:>8.1f}% {m['a club 3+ times']:>8.1f}% "
          f"{m['same club-season twice']:>14.1f}% {m['distinct clubs']:>15.2f}")
