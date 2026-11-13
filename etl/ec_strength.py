"""
Team strength per European Cup club, on the scale the league engine already uses.

    python ec_strength.py 2023        print strengths, write ec/ec_strength_2023.tsv

STRENGTH is the mean rating of a club's best CORE_SQUAD (14) players, exactly as
squad_strength.py defines it for the leagues, here taken from the capped
25-man rosters in ec/ec_players_<season>.tsv.

CLUBS WITH NO ROSTER (Red Star Belgrade: EA licenses no Serbian league). Their
strength is ESTIMATED FROM THEIR OWN RESULTS against rated opponents. A model
goal difference = k * (strength_home - strength_away) + home_advantage is fitted
on every match between two rated clubs; each unrated club's matches then each
imply a strength (opponent's strength + the unexplained goal difference / k),
and the estimate is their mean. Six matches is a thin sample, so the estimate is
pulled toward a prior (the mean of the weakest rated qualifiers) with a weight
that grows with the sample's noise, and the result is stored with its source and
its standard error so nobody mistakes it for a measured squad.
"""
from __future__ import annotations

import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

import ec_squads

HERE = Path(__file__).parent
CORE_SQUAD = 14


def strengths(season: int) -> dict[str, float]:
    rows = list(csv.DictReader(open(HERE / "ec" / f"ec_players_{season}.tsv", encoding="utf-8"), delimiter="\t"))
    by = defaultdict(list)
    for r in rows:
        by[r["club"]].append(int(r["rating"]))
    return {c: sum(sorted(v, reverse=True)[:CORE_SQUAD]) / CORE_SQUAD for c, v in by.items()}


def main() -> None:
    season = int(sys.argv[1]) if len(sys.argv) > 1 else 2023
    known = strengths(season)
    ms = json.load(open(HERE / "wikipedia" / f"ec_matches_{season}.json", encoding="utf-8"))["matches"]
    clubs = {m[s] for m in ms for s in ("home", "away")}
    unrated = sorted(clubs - set(known))
    print("rated:", len(known), " unrated:", unrated)

    # Fit GD = k*(sH - sA) + h on rated-vs-rated matches (the final is neutral: h off).
    X, y = [], []
    for m in ms:
        if m["home"] in known and m["away"] in known:
            X.append([known[m["home"]] - known[m["away"]], 0 if m["stage"] == "final" else 1])
            y.append(m["home_goals"] - m["away_goals"])
    X, y = np.array(X), np.array(y, float)
    (k, h), *_ = np.linalg.lstsq(X, y, rcond=None)
    sd = float(np.std(y - X @ np.array([k, h]), ddof=2))
    print(f"fit on {len(y)} matches: k={k:.3f} goals per rating point, home advantage={h:.2f}, residual sd={sd:.2f}")

    # Prior for an unrated club: the mean of the four weakest rated qualifiers.
    prior = float(np.mean(sorted(known.values())[:4]))
    est = {}
    for club in unrated:
        implied = []
        for m in ms:
            if club not in (m["home"], m["away"]):
                continue
            opp_home = m["home"] != club
            opp = m["home"] if opp_home else m["away"]
            if opp not in known:
                continue
            # Goal difference from the CLUB's side: it is the home side unless the opponent is.
            gd = (m["home_goals"] - m["away_goals"]) if not opp_home else (m["away_goals"] - m["home_goals"])
            # gd is from the CLUB's side; home advantage helps whoever hosts it.
            host = 0 if m["stage"] == "final" else (1 if m["home"] == club else -1)
            implied.append(known[opp] + (gd - h * host) / k)
        raw = float(np.mean(implied)); se = sd / k / math.sqrt(len(implied))
        # Precision-weighted blend with a prior whose own spread is the spread of the weak clubs.
        prior_sd = float(np.std(sorted(known.values())[:8]))
        w = (1 / se**2) / (1 / se**2 + 1 / prior_sd**2)
        est[club] = (w * raw + (1 - w) * prior, raw, se, len(implied), w)
        print(f"{club}: from results {raw:.1f} (se {se:.1f}, {len(implied)} matches); "
              f"prior {prior:.1f}; blended {est[club][0]:.1f} (results weight {w:.2f})")

    out = HERE / "ec" / f"ec_strength_{season}.tsv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        w_ = csv.writer(f, delimiter="\t")
        w_.writerow(["season", "club", "strength", "source", "std_error"])
        for c, v in sorted(known.items(), key=lambda kv: -kv[1]):
            w_.writerow([season, c, f"{v:.2f}", "squad_top14", ""])
        for c, (v, raw, se, n, w) in est.items():
            w_.writerow([season, c, f"{v:.2f}", f"estimated_from_{n}_results", f"{se:.2f}"])
    for c, v in sorted({**known, **{c: e[0] for c, e in est.items()}}.items(), key=lambda kv: -kv[1]):
        print(f"{v:6.2f}  {c}" + ("   <- estimated" if c in est else ""))
    print("->", out.name)


if __name__ == "__main__":
    main()
