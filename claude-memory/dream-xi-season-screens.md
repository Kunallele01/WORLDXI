---
name: dream-xi-season-screens
description: "What the season and statistics screens do after several rounds of user feedback, and the one open design decision (squad depth vs stat normalisation)"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-30T14:24:55.247Z
---

State as of 2026-08-31, on-device, 85 tests passing. Files:
`feature/season/SeasonScreen.kt`, `StatisticsScreen.kt`, `SeasonViewModel.kt`.

**The flow after an XI is finished** (draft or [[dream-xi-free-mode]]):
takeover draw → "Kick off" → matchday by matchday → final table → separate
statistics screen. See [[dream-xi-simulation-engine]] for what is actually
being simulated.

**Details the user asked for specifically, so do not undo them**
- Playing a week **scrolls to the match card at the top**, not to his row in the
  table. The table row moves around as his position changes, so scrolling there
  meant the result he tapped for was never what appeared.
- **"Play the rest of the season" runs at 2,200 ms per matchday**, not instantly,
  and scrolls back to the scoresheet each week. It resumes from wherever he is.
  While running the buttons become **Stop** and **Skip to the end**.
- The reveal line is "X finished 19th. Their fixtures are yours." An earlier
  "Do better." was called cringe — keep the tone flat.
- The **league table columns are Club, GP, W, D, L, GF, GA, GD, Pts**. Nine
  columns need ~390dp and a phone gives 360, so the identity block is pinned and
  the numbers scroll horizontally on ONE shared `ScrollState` (header included).
  Per-row scroll states shear the grid apart.
- **Statistics is its own screen**, reached by a button beside Done; Done asks
  "Leave without the statistics?" first because the run is not saved.
- The user's XI leads that screen, then the league. Per-team lists come from
  `SeasonStats.userScorers/userAssists/...`, built from the full set — filtering
  the league's top ten down to one club is what made it show a single name.
- Most-booked shows "15Y 1R" — the value displayed must be the value sorted on.
  It previously sorted on yellows+3×reds and printed yellows alone, so a season
  read 24, 16, 25, 14, 19 down the page.

**ANALYTICS LAYER, added 2026-09-01** from a second-opinion review the user
commissioned. Four features, all in `sim/` and therefore server-portable:
- `SeasonAnalysis.kt` — expected points vs actual, expected goals vs actual,
  and the home/away split. All ANALYTIC, no simulation: `expectedPointsBoth`
  sums over every scoreline and a whole season costs ~0.1ms. Shown at the top of
  the statistics screen.
- `FixtureOutlook.kt` — a "coming up" run of the next four fixtures with
  opponent position, real recent form and difficulty stars. Difficulty is
  QUINTILES OF THE USER'S OWN 38 fixtures by expected points, not absolute
  bands: a 90-rated XI finds nothing hard and a poor one finds everything hard,
  so fixed thresholds would print the same star count 38 times.
- `SeasonProjection.kt` — Monte Carlo over 1,000 seasons giving title/top-four/
  relegation odds and an expected finish, shown before kick-off and again beside
  the actual result. Runs in **11ms** because the 342 real fixtures are constant
  across seeds and are accumulated once, so each extra season is 38 Poisson
  draws. Computed on Dispatchers.Default during the ~4s takeover-draw animation.
  Validated: projected mean finish 4.66 vs full-simulation mean 4.64.

**NEVER LABEL BEATING xG AS "FINISHING".** The engine models no finishing skill
beyond npxG, so out-scoring expected goals is Poisson variance and nothing else.
The UI says "above expectation". The second-opinion review suggested
"Finishing: +4.8" and that would have the screen assert something the model does
not know.

**RESOLVED 2026-09-01 — the squad now ROTATES; do not reopen this.** The user
asked about normalising his XI's inflated individual totals via "3 imaginary
subs". I argued against phantom subs (team goals must equal the sum of player
goals, so deflating a scorer sends those goals to invented players who then
appear in his own top-scorer list) and he chose the real fix instead: an
**auto-filled bench of eight**, picked from squads the run passed through, with
minutes shared out. See [[dream-xi-simulation-engine]] for the minute model and
[[dream-xi-draft-mechanic]] for how the bench is selected. It is SHOWN under the
pitch, and it changes who scores without ever touching team strength.

**The projection readout shows a CONTIGUOUS band** (`likelyBand`), not the most
probable positions sorted. It used to print things like 2nd, 5th, 6th, 7th, from
which a reader can only conclude third and fourth are impossible. Sometimes they
nearly are and that is worth saying: in a counterfactual the other clubs' points
are fixed history, so a place only exists to be taken if there is a GAP between
two of them — and clubs finish level (2019/20 PL had 3rd and 4th both on 66,
La Liga both on 70), leaving positions no result can reach. A 0% inside the band
now carries that explanation rather than being hidden.

**Cards are NOT modelled beyond being counted.** No suspensions, no accumulation,
no availability — a player sent off plays the next match. Retrofitting it needs
real match-by-match squad state, since minutes here are a season-level model.
The user knows and has not asked for it.
