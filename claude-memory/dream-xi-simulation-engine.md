---
name: dream-xi-simulation-engine
description: "The simulation engine is built, calibrated and on-device: how it works, what it is validated against, and the two numbers that are judgement not measurement"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-29T23:09:43.903Z
---

Built and on-device 2026-08-29/30. **71 unit tests, all passing.** Everything
lives in `app/src/main/java/com/dreamxi/app/sim/` — pure Kotlin with zero
Android imports, so the whole package moves to the Ktor service unchanged.

**THE RULE FOR THIS PACKAGE: no constant that was not measured.** Every value
in `SimModel.kt` is fitted against the 200 real club-seasons in Supabase and
carries its fit quality in a comment. The calibration scripts are
`etl/sim_calibration.py`, `sim_position_shares.py`, `sim_fit.py`,
`sim_events_calibration.py`, `export_sim_fixture.py`.

**How a match is decided**
- **Attack is additive**: an XI's summed npxG/90 predicts real goals at
  **r = +0.864**. Saturates above 3.00 npxG (Man City 2019/20, the highest real
  XI ever measured).
- **Defence is rating-driven, never volume-driven.** Back four r = −0.748, GK
  OVR r = −0.798, weighted by how much each position defends (GK/CB 1.00 →
  ST 0.10). Keeper save% adds independent signal, r = −0.308 on the residual.
  See [[dream-xi-rating-anchor]] — defensive box-score volume is inverted.
- **Out of position**: a player's own rate gives way to the POSITION's rate as
  he is moved, anchored on EA's positional grid. This is what stops ten
  strikers scoring five a game.
- Poisson scoreline, home advantage **1.2452** measured over 3,800 fixtures.

**Validation** (`RealSeasonValidationTest`, 156 real club-seasons replayed
through the shipped Kotlin): league points **r = 0.916, RMSE 7.1**, goals for
r = 0.864, goals against r = 0.853, real champion in the predicted top four in
6 of 6 seasons. Mean XI rating by real finishing position — useful sanity
check when a user reports a result: **1st 85.5, 4th 81.6, 8th 79.1, 20th 74.8.**

**THE SEASON IS A COUNTERFACTUAL, NOT A RE-SIMULATION** (changed 2026-08-30 at
the user's request, and it was the right call). `SeasonSimulator.playCounterfactual`
takes the season's real 380 fixtures from the `fixtures` table (all 3,800 rows
were already loaded — check before assuming data is missing), hands the
replaced club's 38 to the user's XI keeping matchday and venue, and leaves the
other 342 at their real scorelines. Real Madrid still wins 2021/22 unless the
user takes points off them. Do not go back to simulating all 380: the question
is "could my eleven have done it?", which is only meaningful against the season
that really happened.

**Match reports and scorer attribution — the asymmetry is deliberate**
- **Real clubs**: attributed across the WHOLE squad, weighted by real season
  TOTALS. A season total already contains the games a player missed.
- **The user's XI**: per-90 rates over 38 full matches, because a drafted XI
  has no bench, rotation or injuries and genuinely plays every minute.

That asymmetry fixed a reported bug: per-90 weighting plus an eleven-man pool
gave Isak 32 goals (real 21), Chris Wood 30 (real 14), Muniz 30 (real 9),
Trippier 21 assists (real 10). Two compounding causes, both measured: a
top-eleven player plays only **69.6%** of a season, and a top eleven scores only
**68.9%** of its club's goals. `SeasonStatsTest` has a regression guard that
puts Isak back at ~22.

**CARDS DO NOT SCALE WITH MINUTES; GOALS DO.** Yellows go as
`minutes^0.764` (`SimModel.CARD_MINUTES_EXPONENT`). Projecting a part-season
booking rate linearly onto a full 38 put Modric on 15 yellows off a real 7 in
1,744 minutes. Mean yellows per 90 FALLS as a player features more (0.235 in the
450-900 band to 0.151 above 2,900) because a booked player is substituted and one
on a yellow is managed carefully; goals per 90 show no such decline over the same
bands. So the correction is applied to cards ONLY — there is a test that fails if
anyone routes goals through it.

**Derive that exponent from the BAND RATES, never a log-log regression.** The
regression conditions on a non-zero count, so it only sees players who were
booked at all and skews toward low-minute players: it returns 0.612 for yellows
and a spurious 0.565 for GOALS, which the unbiased band means prove is linear.
`etl/card_scaling.py` and `etl/defence_check.py` hold this work.

**Defence is right, and the check exists if it is queried again**
(`etl/defence_check.py`). Real sides at a weighted defensive rating of 80 concede
a median of 46 in a season; the model says 47. The spread at that rating is
34-63, so a mean side like Getafe cannot be distinguished from a leaky one — the
columns that would explain organisation (clearances, aerials, blocks) are exactly
the ones FBref stripped. Note the rating is a weighted mean over ALL ELEVEN
(GK/CB 1.00 down to ST 0.10), so "my defenders are all 80+" does not mean a
rating of 85.

**THE SQUAD ROTATES — this is built, not open.** Eleven men playing every minute
of all 38 took every goal their side scored, so a drafted forward out-scored his
real self by about half. `SquadProfile.fromSquad(xi, bench)` now shares minutes
out and the whole thing is measured:

- **Starters get their POSITION's share** (`STARTER_MINUTE_SHARE_BY_ROLE`):
  GK 1.00 (no backup, so he plays everything), CB .710, CAM .705, DM/CM .690,
  FB .672, Winger .651, ST .648. Minutes track position, not quality — the
  correlation between rating and minutes INSIDE a starting XI is only r = +0.36.
- **The bench is a GRADED CURVE** (`BENCH_MINUTE_CURVE`), the real rank-12-to-19
  shape running 44.1% of a season down to 21.1%, ordered by effective rating.
  Flat shares gave eight substitutes eight near-identical tallies, which no
  squad list ever looks like.
- **The keeper is held OUT of the scaling.** Including him pushed him past 100%
  and redistributed his surplus onto the outfielders, moving the starter/bench
  split from the measured 72/28 toward 74/26 and handing the striker back the
  share the bench exists to take off him.
- **Capping must redistribute.** With no bench all ten outfielders need 100%;
  clipping the overflow left the side playing 10.86 men.
- Result: bench takes **27.4-27.9%** of goals (real 27.7%), and a lead striker
  drops from 30.8 to 24.2 over 20 seasons.

**WHEN an event happens depends on whether the player STARTED.** A substitute
cannot score in the third minute, but goal minutes were drawn uniformly across
the ninety whoever scored. The answer is NOT "substitutes score late": a squad
player on a 44% share still STARTS 64% of his appearances. So the engine decides
per event, from a fitted relationship (`SimModel.startRate`, n = 4,000):

    start rate = 0.3231 + 0.7662 * minutes share

If he started, the minute is anywhere in the ninety; if he came on, only after
`SUB_ENTRY_EARLIEST` (58, a judgement — nothing records substitution minutes).
The same rule governs bookings and assists.

**Two traps that came with it.** The weighted draw falls back to a UNIFORM pick
when all weights are zero, which defeated the assist mask entirely — a goal must
have a scorer so that fallback is right there, but an assist has no such
requirement and now returns "unassisted" (`pickOrNull`). And match RESULTS and
match REPORTS must stay on separate random streams (`EVENT_STREAM_OFFSET`):
they shared one generator, so changing how a minute was drawn consumed a
different number of randoms and reshuffled entire league tables.

**TWO NUMBERS ARE JUDGEMENT, NOT MEASUREMENT**, and both say so in the source:
`ATTACK_SATURATION_ASYMPTOTE = 3.30` (no data exists above 3.00) and
`POSITION_TRANSFER_RANGE = 25.0`. Everything else has a correlation attached.

**Seeds must be scrambled** (`sim/Seeding.kt`, SplitMix64). Run ids are
sequential and Kotlin's generator correlates across neighbouring seeds — feeding
them in raw skewed the takeover draw to 35.5% / 33.3% / 31.2% instead of an even
third. Invisible in play, caught only by a uniformity test over 6,000 seeds.

**The takeover draw** (`sim/TakeoverDraw.kt`): the user replaces one of the
season's three REAL relegated clubs, chosen by 300 successive draws with a
sudden-death tie-break, per [[dream-xi-draft-mechanic]]. Long on purpose — the
user asked for a drawn-out reveal, not a coin flip — but exactly 1-in-3.

Known limitation, recorded in code: the engine has no bench, so for the user's
own XI all goals go to eleven men. Own goals and squad players are ~3% of a real
club's total.

## Assists come from a season budget, not a per-goal draw (2026-09-12)

Reported bug: Mesut Ozil finished a simulated 2010/11 La Liga with 26 assists (real: 16; no real season in the 30 loaded has EVER exceeded 21). Fixed by allotting each club a season assist budget and dealing it out as goals arrive, instead of drawing an assister afresh for every goal.

- Real clubs: budget = their players ACTUAL assists. In a counterfactual 342 of 380 matches really happened, so this is truth, not a model. Measured after the fix: Ozil exactly 16.0 (sd 0.0), De Bruyne 20.0, squad totals exactly 67 and 69, nothing above 21, 0% of seasons over 21.
- User XI (invented season): budget = per-match rates x 38, RESCALED to ASSISTED_GOAL_RATE x expected goals (rating.attack x 38), allocated by LARGEST REMAINDER.
- Cards deliberately untouched (user's call). Goals were already right — simulated top scorer 40.5 vs Ronaldo's real 40 — and were not changed.

**Why:** independent per-goal draws are far wider than football. Real top-provider totals over 30 league-seasons: mean 15.3, sd 2.5, range 12-21. The old draw ran sd ~4 and put 24-46% of seasons above 21.

**How to apply — three things I got wrong before getting it right, all worth remembering:**
1. I said masking the scorer only REDISTRIBUTED assists. It also inflated the squad total (72 vs a real 67). Check totals, not just the headline player.
2. My first fix (draw from unmasked weights, unassisted if ineligible) fixed the provider (15.2) but lost assists (58 vs 67); adding retries to recover the total pushed the provider straight back to ~18. No setting of that dial fixed both — the shape was wrong, not the parameter.
3. The invented-season failure was different again and only a diagnostic run found it: budget 25 vs clubs scoring 29-51 goals, so 500 credits covered 854 goals and the league assisted share collapsed to 58.5% (vs 70.4%). Per-player rounding also silently barred anyone whose rate rounded to zero — two CBs and a keeper could never assist all season. Hence scaling to expected goals + largest remainder.

Verified: 186 tests green, assisted share 72.4% vs real 70.4%. See [[dream-xi-simulation-engine]].

## Goals come from a season budget too — and why I missed it (2026-09-12)

Reported: Mohamed Salah (real Liverpool, not the user squad) finished a simulated 2017/18 on 42 goals. Real: 32. No Premier League season has ever reached 42.

**I had explicitly checked scorers an hour earlier and cleared them.** That check was invalid: I compared the simulated spread of a RE-RUN club-season against how much top scorers vary across DIFFERENT seasons and clubs (sd 7.8). Those are different quantities. Re-running one real club-season there is no distribution to match — the goals were really scored. Measured properly: the per-goal draw gave Salah 21-47 around his real 32, and Haaland 23-53 around his real 36.

Fix mirrors assists exactly: `SquadProfile.goalBudget` + `newGoalLedger()`, spent in `MatchReporter.scorers`. Real clubs get their ACTUAL goals (Salah now lands on 32 in every run); the user XI gets rates x 38 scaled to expected goals, largest remainder. ONE asymmetry with assists: a goal must always have a scorer, so an exhausted budget falls back to the weighted draw — "nobody" is never a valid answer for a goal, while it is correct for ~3 in 10 assists.

**A test had to be rewritten, and the reasoning matters.** `the golden boot lands where real golden boots land` asserted 18-42 (borrowed from real leagues) and failed at 17-21. The budget was NOT losing the striker his share — he takes 32-36% of the budget at every scale, against a measured 35.2% for a top-eleven scorer and 25.7% median across 600 real club-seasons. The fixture league simply scores 29-51 goals a club where real clubs score 35-100, so a 23-36 golden boot is unreachable in it and the old band was only ever met by noise in the draw. Rewritten to assert the SHARE (scale-free, measured) instead of the raw total.

Verified: 191 tests green; golden boot 18 of 51 club goals (35.3%); leading scorer 34.1% vs real 35.2%; assisted share 72.4% vs real 70.4%.

**Lesson worth keeping:** when a draw reproduces the right MEAN, that says nothing about whether its spread belongs there. Ask what the right answer is for the case being re-run — for anything that really happened, the answer is a fact, not a distribution.
