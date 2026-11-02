---
name: dream-xi-draft-screen-state
description: "The draft feature is built and approved; what it does, and the three known gaps blocking simulation"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-29T16:08:14.928Z
---

The draft is **built, on-device and signed off** by the user (2026-08-29).
Do not redesign it.

**The simulation it feeds is now built too** — see
[[dream-xi-simulation-engine]]. The draft's completion strip is a "Play the
season" button that hands the XI to `ActiveRun` and navigates to the season.
The next work is [[dream-xi-free-mode]], then auth/persistence.

**What it does now**
- Setup screen picks the league (real data: Premier League + La Liga, 5 seasons
  each), the formation, and the change-spin allowance. Setup is the app's start
  destination.
- 20 real-world formations, grouped by defenders at the back, each drawn as
  eleven dots from the same coordinates the pitch uses. **Locked for the run.**
- Positions are UNLOCKED: any outfielder may fill any outfield position, priced
  by EA's own per-player positional grid. Goalkeeper is locked both ways.
- Tapping a player raises the pitch as a placement sheet; every legal position
  shows what HE would be worth there, coloured by fit (gold natural / green
  ≤3 / amber 4-10 / red >10).
- **View team** button above Spin opens the same pitch with nothing in hand:
  tap a placed player to pick him up, tap elsewhere to move or swap. Ratings
  are derived from the position held, so moving Benzema ST→LW→ST gives
  91→89→91 with no recompute step. Label becomes **"Final edit"** once the XI
  is complete, because the completion screen already shows the pitch.
- Completed XI screen: pitch, XI rating, full squad list, and an honest line
  about what out-of-position picks cost.
- All 53 club crests bundled in `res/drawable-nodpi/crest_<slug>.png`, with a
  generated colour+initials badge as the fallback for clubs added later.

**Three gaps, in the order they should be closed**
1. **Nothing persists.** No auth, no `draft_runs`/`draft_picks` rows. Killing
   the app mid-run loses it — including mid-SEASON, since `ActiveRun` is an
   in-memory singleton that says "This run was lost. Start a new draft." It is
   deliberately shaped so it becomes a cache in front of the database later
   without the screens changing. This is now the biggest gap.
2. **The spin caps are inert.** `draft_eligible_club_seasons` (migration_0004)
   only applies when the ViewModel has a `runId`, which needs auth. Every spin
   currently draws from the whole league pool, uncapped. The feature was
   designed and measured but is not running.
3. ~~Squad strength is natural-rating based.~~ Still true, and still fine: it
   describes a real club's squad and does NOT know about out-of-position play.
   The simulation does not use it — `TeamStrength.rate()` is the XI-strength
   number at simulate time. Do not conflate the two.

See [[dream-xi-missing-stats-sources]] for what data can still be fetched,
[[dream-xi-rating-anchor]] for how ratings are built, and
[[dream-xi-draft-mechanic]] for the spin/reroll rules.

**BACK ALWAYS CONFIRMS, including before the first pick.** It used to quit
outright while the XI was empty, on the reasoning that there was nothing to
lose — but the league, formation and reroll allowance were all chosen at Setup,
so an accidental back gesture on the spin screen threw the user out with no
warning. Free Mode had the same hole and worse: its handler was enabled only
while the picker was open, so backing out of the pitch discarded every pick
silently. Both now ask, and Free Mode's "Back" button routes through the same
dialog so the two cannot drift apart.
