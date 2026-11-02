---
name: dream-xi-world-cup-engine-calibration
description: "Measured constants for the World Cup knockout engine, the FIFA 07 rating bug found on the way, the Wikipedia scorer source, and 2026's different rules"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-09-17T12:43:49.704Z
---

Built 2026-09-17 in `etl/wc_calibration.py` (run it; it prints every number below). The
user said "go ahead with the knockout engine"; these are the inputs. The sim package's rule
still holds: no constant unmeasured. See [[dream-xi-simulation-engine]], [[dream-xi-world-cup-mode]].

**World Cup players are rating-only** (no npxG/goals/save%, and FIFA 07/11/26 have no
positional grid). `coverDelta` returns 0 without a grid, so without a cost table every WC
player would play anywhere free. Rating-only attack = the engine's own documented
missing-data estimator, `positionalYield(role, rating)`.

**Measured (2006-2026 real matches):**
- Out-of-position cost matrix from EA grids FIFA 15-24 (median, primary role -> target, for
  players NOT listing the target). E.g. CB->FB -5, FB->CB -2, ST->CB -22, CAM->CM -4, Winger->ST -4.
  A listed SECONDARY position costs: FB -3, CM -2, ST -2, DM -1, Winger -1, CB 0, CAM 0.
- EA -> role: LM/RM/LW/RW = Winger, CF/LF/RF = ST, LWB/RWB = FB, CDM = DM (as etl/roles.py).
- Stand-in keeper when none rated: keeper = 1.0219 * mean(best 10 outfield) - 2.493
  (r = 0.73, sd 4.47, n = 194). Needed for Angola 06, South Africa/Egypt/Iran 26 (all reachable).
- AI side's XI: Hungarian assignment per formation (all 19 from Formation.kt), formation that
  maximises attack - defence.
- International mapping on the club model's per-team lambda: real = 0.8885 * lambda^0.8057
  (shape term significant, deviance gain 5.54). Neutral venue, no HOME_ADVANTAGE.
  Win/draw/loss log-loss 0.945 vs 1.079 flat; GD r = 0.53; draw rate 24.8% vs real 23.7%.
- Host nation: scoring x1.420 (LR 7.12, significant); conceding x1.15 NOT significant -> 1.0.
- Extra time: per-minute scoring x0.745 vs 90 minutes (95% 0.39-1.37, 37 ties).
- Shootouts: stronger side won 14/23 (p = 0.40) -> coin flip. Conversion 0.655 on ALL 23 shootout scores
  (CI .585-.720); first measured 0.615 on only 9. The mirror has 2006-2018 shootout WINNERS but not scores;
  Wikipedia boxes have every score, all 23 agree with the winners (`wc_fixtures.with_shootout_scores`).
- KNOCKOUT FACTOR REJECTED: MLE x1.2 is significant but only fits scorelines; it moves
  level-at-90 from 28% toward 25% against a real 33%, i.e. worsens who advances. Real
  knockouts are both drawier AND higher-scoring once broken (game state) — one scalar can't.

**FIFA 07 rating bug (mine, fixed):** `to_modern_scale` applied the 15->17 inflation to
edition 07 because the code path was "edition <= 15"; it was only ever measured for 10-16.
FIFA 07 was ALREADY on the modern scale, so 2006 squads came out ~2 points too strong and
swept the strongest-team list. Fixed in `rating_scale.py` with a RANK-matched correction
(player at rank k vs modern editions 17-26 at rank k), validated because FIFA 15/16 land
within +/-0.4 by rank after their same-player correction. Top 1000 ranks only: FIFA 07 has
8,730 players vs ~18k, so deeper ranks measure missing leagues. Club corrections unchanged.
**FIFA 11's top 50 read ~1.0 low by the same check — deliberately NOT changed**, because the
approved same-player method serves the 2010/11 club seasons and the player would differ by mode.

**Suspected FIFA 11 roster-date drift (not fixed, flagged):** after the WC scrape merge the
same-player 10->11 step reads -1.00 vs the -0.6..+0.1 recorded when fitted. The merge
overwrote club-scrape FIFA 11 values by id; likely a later sofifa roster update. Loaded club
seasons are unaffected; re-staging 2010/11 would now shift ratings slightly.

**Wikipedia is the scorer + letters source** (user chose real scorers). `etl/wc_wikipedia.py`
parses 68 raw pages in `etl/wikipedia/raw/` (group pages, knockout pages, and transcluded
pages: all finals, 2026 round of 32, Battle of Lusail, Battle of Nuremberg, Brazil v Germany
2014). CC BY-SA 4.0 — **UI must credit "Match data: Wikipedia, CC BY-SA 4.0"**. Verified:
1,112 goals, every match's goals sum to its real score, 2022 final exact. Two markup
generations ({{goal|..}} vs plain "9'"); 2022 flags use {{#invoke:flagg|...|QAT}}; one 2022
box writes Spain as SPA. Needs `migration_0008_world_cup_goals.sql` (user runs it).

**2026 rules differ:** tiebreak is head-to-head FIRST (h2h pts, GD, GF, reapplied to the
still-level subset), then overall GD, GF, fair play, FIFA ranking. 2006-2022: pts, GD, GF,
then h2h pts/GD/GF, then fair play (2018+) / lots. No card or ranking data -> seeded lots.
2026 round of 32 slots (Match 73-88) and FIFA's 495-row third-place table are in
`etl/wikipedia/wc2026_third_place.tsv`, cross-checked against the published slot
constraints; row 67 reproduces reality. The real letters replaced the date-ordered guesses.

**Group positions verified**: every real knockout entrant is our 1st/2nd, no 1v1/2v2
pairings pre-2026, and our best-eight-thirds ranking equals the real eight.

## Engine BUILT 2026-09-17 (sim/worldcup/, 214 unit tests green, verified from fresh XML)
Files: WcModel (constants), NationalSide (position costs, stand-in keeper, best lineup over all 19
formations via Hungarian — moved to sim/Hungarian.kt so sim has no UI deps), WcMatchEngine (90/ET/
shootout), WcGroupTable (OVERALL_FIRST vs 2026 HEAD_TO_HEAD_FIRST with re-application), WcBracket,
WcTournament (counterfactual cascade). GENERATED: WcBrackets.kt + WcThirdPlaceTable2026.kt by
`etl/export_wc_engine_data.py`, which also exports app/src/test/resources/wc/*.tsv.
- Brackets are derived from real results, never typed; 2006-2022 come out identical; 2026 R32 matches
  FIFA's Match 73-88 list.
- Replay with nobody replaced reproduces all 6 tournaments exactly (champions Italy, Spain, Germany,
  France, Argentina, Spain). Kotlin log-loss 0.9460 vs Python 0.9446.
- Untouched groups keep REAL order (a fair-play tie like Japan/Senegal 2018 must not be re-drawn).
  The user inherits a host's advantage (Qatar 2022 is bottom, takeable). Shootout carries the WINNER
  explicitly — deriving it from pens once turned Italy's 2006 final into a draw.
- Simulated goals: weighted by SquadProfile.fromXi, no season budget, no pen/og split.

**DECIDED 2026-09-17 by the user: "keep it faithful" — do not tune.** Was raised as: the best possible cross-era XI (Buffon, Ramos, Terry,
Cannavaro, Modric, Iniesta, Robben, Messi, Ronaldo, Neymar, Hazard) wins 19% of 520 takeovers and exits
in groups 14%; Argentina 2018 (strongest real side) wins 5.8%. Measured-faithful (real favourites win
~1 in 5) but the same shape as the club complaint "best ever team comes second". Do NOT tune it. The dream XI margin (+1.67) is only just beyond the best real side (+1.44).

migration_0008 applied by the user 2026-09-17; 1,112 goals, 8 host flags and all 23 shootout scores
LOADED and verified through the anon key (2022 final scorers exact, anon writes 401). No WC UI/repository yet.

## Per-player positional grids + flank cost (2026-09-17, user-approved after reporting Rodri at LB)
- Cause: one MEDIAN cost table priced every DM -2 at FB; Rodri's own EA grid says -7. Medians were dominated by
  low-rated players (DM->FB: -2 under 75, -4 at 85+).
- Now: `wc_squads._with_grid` attaches EA's grid as DELTAS from the primary role — own edition, else the same sofifa
  id's nearest edition WITHIN 2 (`MAX_GRID_BORROW_GAP`), else none -> rating-banded fallback, GENERATED as
  `sim/worldcup/WcPositionCosts.kt` by wc_calibration.py section 4. Coverage (outfield): own 48%, borrowed 20%, fallback 31%.
- Why the cap, measured on real results (group log-loss): banded table 0.9450, own grids 0.9505, borrow<=2 0.9500,
  borrow any gap 0.9559. Grids cost a little fit (inside noise); long borrows cost more. User wanted realism; accepted.
- Flank cost reused from the club draft (FB 4, winger 2), judgement; on real results it is harmless (0.9456 -> 0.9450).
- REFITTED constants: mapping 0.9031 * l^0.7755, host x1.453, ET 0.739, knockout factor still rejected (x1.196).
  Kotlin parity log-loss 0.9511 vs Python 0.9500.
- Engine API: `WcSlot(role, side)`; `NationalSide.positionCost(player, role, side)`; formations passed as WcSlot lists;
  `CompletedWcDraft.placed: List<Pair<WcSlot, WcPlayer>>`. Draft/magic see the same number via SquadPlayer grid + side.
- Found on the way, CLUB MODE TOO: `MagicXi.ratingAt`/`plays` used `coverDelta` (role only, no flank) while pitch,
  season and tournament charge the flank — magic put a right-back at left-back for free. Now uses `deltaAt(slot)`.
- migration_0009_world_cup_grids.sql adds grid_* smallint + grid_edition to wc_squad_players; the app SELECTS those
  columns, so World Cup screens fail until it is run and `python load_wc.py --load` repopulates.

## Rating -> output curve, WORLD CUP ONLY (2026-09-18, after the user reported a 92 XI losing in the R16)
Root cause of "92-rated squad crashes out": WC players have no stats, so output was estimated by the CLUB engine's
linear fallback (position median x 3%/rating point). Measured against real club seasons that rule is badly wrong at
the top: ST 88+ real 0.659 npxG/90 vs 0.507 estimated; WINGER 88+ real 0.572 vs 0.283 (-51%). A 92-rated XI rated
1.71 expected goals against England 2018's 1.81 — the model could not tell a dream XI from a good real side.
- FIX (`etl/wc_output.py` -> generated `sim/worldcup/WcOutput.kt`, used ONLY by `NationalSide.simPlayer`):
  rate = base * exp(slope * (rating - role median)), fitted per role on per-band means of real club seasons
  (900+ min), r2 0.78-0.99. Slopes/pt: ST 7.3%, Winger 7.7%, CM 8.4%, DM 8.0%, CAM 5.4%, FB 5.3%, CB 3.5%.
  Each curve is HELD FLAT above the highest band it measured (DM to 85, ST to 91, Winger to 95) — no extrapolation.
- **CLUB MODE UNTOUCHED** (user's constraint): `TeamStrength.positionalYield` and every club constant unchanged;
  club players have real xG and only ~5% use the fallback. The out-of-position half of the blend still uses the club
  table in both Python and Kotlin — deliberate, keeps them in parity.
- Refit after: mapping 0.9147 * l^0.7330, host x1.468, ET 0.685. Fit on real results IMPROVED: log-loss 0.9500 ->
  0.9386, GD r 0.520 -> 0.532, draw rate 0.238 vs real 0.237. Knockout factor now x1.078, LR 1.10 (no longer
  significant) so rejecting it needs no judgement.
- Effect: 92-rated XI attack 1.71 -> 2.48; v England 2018 45% -> 52%, v Senegal 53% -> 63%, v Panama 87%.
  Dream XI attack 2.20 -> 3.07, titles 17% -> 21% of 520 runs, group exits 14% -> 9%.
- CHECKED, not assumed: the compression exponent is NOT extrapolation — raw expected goals in the fitted 300 matches
  run 0.34 to 6.88 (median 1.47), so a dream XI's 1.8-4.5 is inside the measured range. Left as measured.
- UI bug fixed same day: the third-place match was folded into the "Final" group and sorted first, so the play-off's
  score printed under the Final heading above the final's. It now has its own heading, before the final.
