---
name: dream-xi-draft-mechanic
description: "Dream XI's core draft mechanic — every round spins a random SEASON too, not just a club, within a fixed league"
metadata: 
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-29T04:47:05.359Z
---

The single most important gameplay mechanic, clarified 2026-08-28 (this
supersedes the original PROJECT_SPEC.md/v2 §2 framing, which implied one
sim year chosen up front at Setup):

- **League is fixed at Setup** (e.g. "La Liga"). **Season is NOT fixed** —
  every draft round independently spins a random (season, club) pair from
  that league's entire loaded history. One draft can mix 2011 Real Madrid
  in round 1 with 2022 Sevilla in round 4. This cross-era mixing is the
  actual point of the game.
- **Player identity is unique across the whole draft, not per pick.** Once a
  real person is drafted (in any season), he must be excluded from every
  later round's eligible pool even under a different season/club — enforce
  by excluding already-picked `player_id`s, not `player_season_stat_id`s.
- **The simulated season is chosen AFTER the draft completes**, at simulate
  time: a random season of the chosen league (among those with full data)
  is picked, independent of which seasons the drafted players came from.
- **Which club the XI replaces is also randomized**: a random pick among
  that chosen season's real bottom-3 (18th/19th/20th — the actual
  relegation zone that year), not always dead-last as originally spec'd.

**Schema implication (applied via `supabase/migration_0002_random_season_draft.sql`):**
`draft_runs.season_scope` and `draft_runs.replaced_club_season_id` were
removed — neither makes sense at draft time anymore. `replaced_club_season_id`
now lives on `simulation_results` instead, alongside `simulated_season_id`,
since both are decided together at simulate time.

**Squad-strength weighting was planned, then MEASURED AND REJECTED
(2026-08-29). Do not rebuild it.** The worry was that a run could hand the
player all-elite or all-relegation squads. Simulated 6,000 full drafts
(`etl/draft_sim.py`, using the real mechanic — spin a squad, pick any player
into any open slot):

    >=8 of 11 spins from the bottom quartile ("all-worst"): 0.27%  (1 in 370)
    >=8 of 11 spins from the top quartile    ("all-best") : 0.13%  (1 in 770)
    worst XI mean in 6,000 runs: 78.1 vs a median of 81.5 — only 3.4 apart

Eleven spins plus free slot assignment already averages the luck out. Worse,
adding the weighting *compressed* the XI-mean spread from 3.7 to 2.1 points
while leaving the weakest pick unchanged — i.e. it made every draft more
identical, which is the opposite of what the game wants. The real design gap
is too LITTLE consequence between runs, not too much.

**CHOSEN INSTEAD — "change spins" (rerolls).** The player may reject a spun
squad outright and spin again. Allowance is a difficulty setting, 1-3,
default 2, exposed in game Settings. Measured effect: roughly **+0.4 XI
rating per reroll** (0→81.5, 1→81.9, 2→82.3, 3→82.5) — a gentle dial where
no setting trivialises a run. Two honest limits, both measured:
- It does NOT raise the weakest pick in an XI (stuck near 77 at every
  setting). Late in a draft only awkward slots remain and a reroll just
  redraws from the same distribution.
- There is no clever way to spend them: "use early" 82.2 vs "save for the
  last 4 rounds" 82.0, and the reroll threshold barely matters either.
It is an agency/frustration-relief feature, not an optimisation lever —
which is exactly how the user framed it. Schema in
`supabase/migration_0003_draft_rerolls.sql` (per-run allowance so changing
the setting later can't retroactively rewrite a finished run, plus a
`draft_rerolls` table recording which squads were rejected).

**REPEAT CLUBS ARE WEIGHTED DOWN, NEVER EXCLUDED** (2026-08-31, and the user
corrected me on this — do not "fix" it back to an exclusion). The pool
originally had no memory at all: 43.6% of drafts drew the IDENTICAL club-season
twice and 28.5% saw some club three times. A hard ban fixed that and overshot,
making repeats impossible when the user only wanted them unlikely. Now each
club-season carries a multiplicative weight (`feature/draft/SpinWeighting.kt`):
`CLUB_REPEAT_WEIGHT = 0.25` per prior appearance of the club, times
`CLUB_SEASON_REPEAT_WEIGHT = 0.05` per prior appearance of that exact
club-season. Measured over 200,000 drafts (`etl/spin_repeat_weights.py`): a club
repeats in 56.5% of drafts, appears three times in 0.6%, and the same
club-season repeats in 0.9%. Weights are always strictly positive — nothing is
ever removed from the pool.

**Why this matters:** if building the Draft/Setup/Simulate screens or their
ViewModels, this mechanic is central — don't reintroduce a fixed-season
Setup step or default to always-last-place replacement. See
[[dream-xi-architecture-decisions]], [[dream-xi-supabase-project]].

**AN AUTO-FILLED BENCH OF EIGHT** joins the XI when the draft completes
(`feature/draft/BenchSelection.kt`). The user never picks them — eight more
rounds of choosing players nobody cares about would tax the part of the draft
that works. Rules:
- Pool = squads the run actually TOOK A PLAYER FROM. A squad that was spun and
  rerolled away does not stock the bench; the user turned it down.
- Positions come from the FORMATION, most-fielded first, then cycling — so 3-5-2
  gets wing-back cover and 4-2-3-1 a second holding midfielder. A fixed list
  would be right for 4-3-3 and wrong for the other eighteen shapes.
- **A player whose RECORDED position matches the slot always wins**, with
  conversions only when nobody natural is left. Ranking on adjusted rating alone
  sent 29% of slots to a converted player and produced Lewandowski as winger
  cover — see [[dream-xi-fifa-grid-faults]].
- No goalkeeper: a backup scores nothing and is booked once every sixteen games.
- Identity stays unique across the whole run, bench included.

Free Mode fills its bench the same way, from the clubs the user picked from.
