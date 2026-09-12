"""
Puts FIFA ratings from editions 10-16 onto the FIFA 17+ scale.

WHY THIS EXISTS. EA inflated its ratings between FIFA 15 and FIFA 17 and left
them flat either side. Following the SAME players at prime age (26-29, when
real ability barely moves) in the Premier League or La Liga across consecutive
editions, the mean change is:

    FIFA 10->11 ... 14->15   -0.6 .. +0.1 per step   (flat)
    FIFA 15->16              +1.57
    FIFA 16->17              +1.34
    FIFA 17->18 ... 23->24   -0.3 .. +0.4 per step   (flat)

That step made every season anchored on FIFA 15 or earlier read about three
points weak — 2009/10-2013/14 squads averaged ~77 against ~79 from 2015/16,
and 97 of their 200 club-seasons landed in the draft's bottom strength tier —
for reasons that have nothing to do with football. The sofifa scrape is not
the cause: the same check across the scrape-to-Kaggle switch (FIFA 14->15)
shows -0.05.

WHY IT IS MEASURED PER RATING BAND. EA did not inflate everyone equally, and
ordinary year-to-year drift is not equal either: low-rated players tend to rise
and high-rated ones to fall (regression to the mean). So each band's inflation
is measured against THAT BAND'S OWN normal drift — stars against stars.

A FIRST VERSION FITTED STRAIGHT LINES and was wrong at the top. One line for
the normal drift through all players predicted stars fall by about a point a
year when they really fall by 0.3, and one line for the inflation could not
follow its U shape (squad players inflated most, mid-table least, stars in
between). Its own before/after check still showed +1.1 of inflation left in
the 85+ band, and it would have moved FIFA 12's 94-rated Messi to 94.3 rather
than the ~96 the band measurement supports. Kept here as the reason the bands
exist.

HOW:
  * normal drift per band, from every non-inflation edition pair;
  * for each inflation step, the band's mean change minus that normal drift;
  * the correction is interpolated linearly between band centres (each band's
    mean rating) and held flat beyond the outermost ones, so neighbouring
    ratings never jump;
  * a rating from edition <= 15 takes both steps in turn, the second applied at
    the already-lifted value; edition 16 takes only the second; 17+ unchanged.

Approved by the user 2026-09-12 (option 2 of three). Run this file for the
fitted corrections and the before/after check.
"""
from __future__ import annotations

import csv
import sys
from collections import defaultdict
from functools import lru_cache
from pathlib import Path

FIFA_CSV = Path(__file__).parent / "fifa" / "fifa_ratings_normalized.csv"
INFLATION_STEPS = (15, 16)          # the steps FIFA 15->16 and FIFA 16->17
LEAGUES = ("premier league", "la liga", "laliga")
PRIME_AGE = (26, 29)
BANDS = [(0, 69), (70, 74), (75, 79), (80, 84), (85, 99)]


def _band(rating: float) -> int:
    for i, (lo, hi) in enumerate(BANDS):
        if lo <= rating < hi + 1:
            return i
    return len(BANDS) - 1


def _editions() -> dict[int, dict[str, tuple[int, int, str]]]:
    rows: dict[int, dict[str, tuple[int, int, str]]] = defaultdict(dict)
    with open(FIFA_CSV, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            ed = int(r["edition"])
            # FC 25 ids are synthesised, so it cannot be followed player to player.
            if ed > 24 or not r["sofifa_id"].isdigit():
                continue
            rows[ed][r["sofifa_id"]] = (int(r["overall"]), int(r["birth_year"]), (r["league"] or "").lower())
    return rows


def _changes(rows, scale=None) -> dict[int, list[tuple[float, float]]]:
    """(rating in edition N, change to N+1) for prime-age players in both leagues, per step N."""
    out: dict[int, list[tuple[float, float]]] = defaultdict(list)
    for ed in sorted(rows)[:-1]:
        a, b = rows[ed], rows.get(ed + 1, {})
        for sid, (ova, by, lga) in a.items():
            if sid not in b:
                continue
            ovb, _, lgb = b[sid]
            age = 2000 + ed - 1 - by
            if not (PRIME_AGE[0] <= age <= PRIME_AGE[1]):
                continue
            if not (any(l in lga for l in LEAGUES) and any(l in lgb for l in LEAGUES)):
                continue
            ra, rb = (float(ova), float(ovb)) if scale is None else (scale(ed, ova), scale(ed + 1, ovb))
            out[ed].append((ra, rb - ra))
    return out


def _band_means(points: list[tuple[float, float]]) -> list[tuple[float, float, int]]:
    """Per band: (mean rating, mean change, n)."""
    groups: dict[int, list[tuple[float, float]]] = defaultdict(list)
    for r, c in points:
        groups[_band(r)].append((r, c))
    return [(sum(r for r, _ in g) / len(g), sum(c for _, c in g) / len(g), len(g))
            for i, g in sorted(groups.items())]


def _excess(changes) -> dict[int, list[tuple[float, float, int]]]:
    """Per inflation step, per band: (band centre, mean change minus that band's normal drift, n)."""
    normal = {i: c for i, (_, c, _) in zip(range(len(BANDS)),
              _band_means([p for ed, pts in changes.items() if ed not in INFLATION_STEPS for p in pts]))}
    out = {}
    for step in INFLATION_STEPS:
        groups: dict[int, list[tuple[float, float]]] = defaultdict(list)
        for r, c in changes[step]:
            groups[_band(r)].append((r, c))
        out[step] = [(sum(r for r, _ in g) / len(g), sum(c for _, c in g) / len(g) - normal[i], len(g))
                     for i, g in sorted(groups.items())]
    return out


@lru_cache(maxsize=1)
def corrections() -> dict[int, list[tuple[float, float, int]]]:
    return _excess(_changes(_editions()))


def _interpolate(points: list[tuple[float, float, int]], rating: float) -> float:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    if rating <= xs[0]:
        return ys[0]
    if rating >= xs[-1]:
        return ys[-1]
    for i in range(1, len(xs)):
        if rating <= xs[i]:
            t = (rating - xs[i - 1]) / (xs[i] - xs[i - 1])
            return ys[i - 1] + t * (ys[i] - ys[i - 1])
    return ys[-1]


def to_modern_scale(edition: int | str, rating: float) -> float:
    """A rating from `edition`, expressed on the FIFA 17+ scale. Unchanged for 17 and later."""
    ed = int(edition)
    value = float(rating)
    table = corrections()
    for step in INFLATION_STEPS:
        if ed <= step:
            value += _interpolate(table[step], value)
    return min(value, 99.0)


def _report() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    for step, pts in corrections().items():
        print(f"inflation at FIFA {step}->{step + 1}, by band (centre rating: excess over that band's normal drift):")
        print("   " + "   ".join(f"{x:.1f}: {y:+.2f} (n={n})" for x, y, n in pts))

    print("\ncorrection applied (rating on FIFA 17+ scale minus original):")
    print(f"   {'rating':>6} {'from FIFA <=15':>15} {'from FIFA 16':>13}")
    for r in (55, 60, 65, 70, 75, 80, 85, 90, 94):
        print(f"   {r:6} {to_modern_scale(15, r) - r:+15.2f} {to_modern_scale(16, r) - r:+13.2f}")

    rows = _editions()
    for label, scale in (("BEFORE", None), ("AFTER", to_modern_scale)):
        excess = _excess(_changes(rows, scale))
        print(f"\n{label}: inflation left over, per band, summed across both steps")
        merged: dict[int, list[float]] = defaultdict(list)
        for step in INFLATION_STEPS:
            for i, (x, y, n) in enumerate(excess[step]):
                merged[i].append(y)
        for i, (lo, hi) in enumerate(BANDS):
            if merged.get(i):
                print(f"   {lo:>2}-{hi:<2}  {sum(merged[i]):+.2f}")
        per_step = {ed: sum(c for _, c in pts) / len(pts) for ed, pts in sorted(_changes(rows, scale).items())}
        print("   mean same-player change per step: " +
              "  ".join(f"{ed}->{ed + 1} {v:+.2f}" for ed, v in per_step.items()))


if __name__ == "__main__":
    _report()
