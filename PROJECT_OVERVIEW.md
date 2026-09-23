# Dream XI — Project Overview

*A reverse-engineered description of the app as it actually exists, written to be handed
to another model for a second opinion. Everything below is read off the codebase and the
live database, not from a plan. Last verified 2026-09-01.*

---

## 1. What the app is

A native Android game. The player assembles an all-time XI from **real historical
footballers, season by season**, then drops that XI into a **real league season** and finds
out whether it could have won it.

The two things that make it different from a fantasy-football or Football-Manager clone:

1. **Players are season-specific.** Not "Messi" but "Messi, Barcelona 2011/12". The same
   person in a different year is a different card with different numbers.
2. **The simulated season is a counterfactual, not a re-simulation.** Only the user's own
   38 matches are played by the engine. The other 342 keep their real historical
   scorelines, so Real Madrid still wins 2021/22 unless the user personally takes points
   off them.

**Status:** playable end to end on a physical device. 85 unit tests, all passing. No auth,
no persistence — a run lives in memory and dies with the process.

---

## 2. Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin 2.3.21, Jetpack Compose (Material 3) |
| DI | Hilt 2.60.1 |
| Navigation | Navigation Compose, single-activity |
| Backend | Supabase (Postgres + PostgREST + RLS), `supabase-kt` |
| Local | Room (declared, not yet used) |
| Images | Coil |
| Build | AGP 9.2.1, `minSdk 24`, `targetSdk 36`, appId `com.dreamxi.app` |
| ETL | Python (requests, pandas, pyreadr, dotenv) — offline, not shipped |

The whole `sim/` package is **pure Kotlin with zero Android imports**, so it can move to a
server (Ktor was the intended target) without touching the app.

---

## 3. Data

### 3.1 What is loaded

| Table | Rows |
|---|---|
| `leagues` | 2 — Premier League, La Liga |
| `seasons` | 10 — 2019/20 … 2023/24, both leagues |
| `club_seasons` | 200 |
| `fixtures` | 3,800 — every real match, with matchday and score |
| `players` | 2,364 |
| `player_season_stats` | 5,723 |

### 3.2 Per-player columns actually populated

Fully populated for all 4,117 "regulars" (≥450 minutes): `minutes`, `appearances`,
`goals`, `assists`, `yellow_cards`, `red_cards`, `shots`, `shots_on_target`, `tackles`,
`save_pct`, `clean_sheets`, `primary_position`, `overall_rating`, `nationality`,
`position_side`, and seven `pos_rating_*` columns (EA's rating for that player at each
draft slot).

`npxg` is at **95%** (uniform across all ten league-seasons; the missing 5% are Understat
mid-season transfers that could not be name-matched).

### 3.3 What is permanently missing, and why it matters

`clearances`, `aerials_won/lost`, `dribbles_completed`, `pass_completion_pct`,
`progressive_carries`, `progressive_passes`, SCA/GCA and post-shot xG exist only for
2019/20–2021/22. **FBref stripped them in January 2026** and there is no free replacement:
API-Football was evaluated and rejected (no clearances, no aerials, at any price tier).

The standing rule that follows: **the simulation reads only columns that every season has.**
A player from 2023/24 must not be worse than one from 2020/21 because his data is thinner.

### 3.4 Rating provenance

`overall_rating` comes from **per-season EA/FIFA ratings** (editions 20–24), matched to
players via a tier-cascade (exact name+year → subset → year ±1 → surname → club), with all
strict tiers run across all editions before any fuzzy tier. Coverage 98.9%.

Ratings are **not** derived from box-score stats. That was tried and abandoned: defensive
volume stats are provably *inverted* (good centre-backs make fewer tackles and
interceptions because they position rather than lunge), so any attribute model built on
them ranks bad defenders above good ones.

---

## 4. Core loop — the draft

The signature mechanic:

1. **Setup** — choose a league, a formation, and a change-spin (reroll) allowance of 1–3
   (default 2). All three are then locked for the run.
2. **Eleven rounds.** Each round independently spins a random **(club, season)** pair from
   that league's entire loaded history. One draft can mix 2019/20 Liverpool with 2023/24
   Girona — that cross-era mixing is the point of the game.
3. The spun squad is shown; the user takes **any one player** into **any open position**.
4. **Change Spin** rejects a squad and redraws, costing one of the allowance.
5. When the XI is complete → the season.

### 4.1 Rules that hold

- **Player identity is unique across the whole draft.** Once a real person is taken he is
  excluded from every later round, even under a different club or season.
- **Positions are unlocked.** Any outfielder may fill any outfield position; the cost is
  priced into his rating there rather than forbidden.
- **Goalkeeper is locked both ways.** EA rates keepers ~50 points below themselves
  outfield and vice versa, so allowing it would only be a way to make a mistake.
- **The formation is fixed for the run.** Changing it would silently re-price every pick
  already made.

### 4.2 Out-of-position pricing

Uses **EA's 27 per-player positional ratings**, stored as seven slot columns. The penalty
is a *delta against the player's own natural-position grid value*, not the raw grid value —
EA's positional scale sits slightly below overall (Benzema's overall is 91, his `st` grid
value 89), so anchoring cancels the offset exactly and a player at his own position always
shows precisely his overall.

Fit bands, taken from the measured population rather than taste: adjacent roles cluster at
1–6 points, alien ones at 18–25. Ten points is the empty gap between the two clusters.

| Band | Drop | Colour |
|---|---|---|
| Natural | 0 | gold |
| Comfortable | ≤3 | green |
| Stretch | 4–10 | amber |
| Alien | >10 | red |

**Wrong-flank penalties (2 for wingers, 4 for full-backs) are the only unmeasured numbers
in the rating model.** EA records which flank a player belongs to but its `lw`/`rw` rating
columns are identical for every player in the file, so there is nothing to derive a
magnitude from. Treating that silence as zero let Vinícius Júnior take RW at full rating.

### 4.3 Spin repeat weighting

The pool originally had no memory: 43.6% of drafts drew the **identical club-season twice**
and 28.5% saw one club three times. Repeats are now weighted down multiplicatively, never
excluded:

- `CLUB_REPEAT_WEIGHT = 0.25` per prior appearance of that club
- `CLUB_SEASON_REPEAT_WEIGHT = 0.05` per prior appearance of that exact club-season

Measured over 200,000 simulated drafts: a club repeats in **56.5%** of drafts, appears three
times in **0.6%**, and the same club-season repeats in **0.9%**. Weights stay strictly
positive.

### 4.4 Rerolls — measured, and deliberately weak

6,000 simulated drafts: median XI rating **81.5**, and each reroll is worth **+0.4**
(0→81.5, 1→81.9, 2→82.3, 3→82.5). It provably does **not** raise the weakest pick in an XI,
and there is no clever way to spend them ("use early" 82.2 vs "save for the last four
rounds" 82.0). It is a frustration-relief feature, not an optimisation lever.

**A squad-strength weighting was designed, measured and rejected.** It compressed the XI
spread from 3.7 to 2.1 points while leaving the weakest pick unchanged — i.e. it made every
draft more identical, the opposite of the goal. Do not rebuild it.

---

## 5. Free Mode

A second, unrestricted way to build an XI, alongside the draft rather than replacing it.
Reached from a secondary button on Setup.

Tap a position → **club** and **season** chip rows → the club's full squad, each player
showing what he would be worth *in that position*. Repeat eleven times.

- The list is **not** filtered to players who suit the position; an odd pick is available
  and visibly costly rather than hidden.
- Kept from the draft (football rules, not difficulty): player uniqueness, out-of-position
  pricing, formation lock, goalkeeper boundary.
- **Deliberately trivial to build a 90-rated XI.** No caps, no budgets, no balancing —
  that is the entire intent.
- Runs are **labelled "FREE MODE"** on the season screens so the achievement is never
  confused with a drafted one.

---

## 6. The simulation engine

`app/src/main/java/com/dreamxi/app/sim/` — 9 files, pure Kotlin.

**The rule for the package: no constant that was not measured.** Every value in
`SimModel.kt` is fitted against the 200 real club-seasons and carries its fit quality in a
comment beside it.

### 6.1 How a match is decided

**Attack is additive.** An XI's summed non-penalty xG per 90 predicts real goals scored at
**r = +0.864**.

```
expectedGoalsFor = 0.8828 × attackRating + 0.2095
```

Saturates above **3.00 npxG** (Manchester City 2019/20, the highest real XI ever measured)
toward an asymptote of 3.30, because beyond that there is no data and chances per match are
physically finite.

**Defence is priced on rating, never on defensive volume.** Volume points the wrong way.

```
expectedGoalsAgainst = −0.0851 × defensiveRating + 8.0418
```

`defensiveRating` is a weighted mean of *effective* ratings across all eleven:

| GK | CB | FB | DM | CM | CAM | Winger | ST |
|---|---|---|---|---|---|---|---|
| 1.00 | 1.00 | 0.85 | 0.70 | 0.45 | 0.20 | 0.20 | 0.10 |

Weighted rather than flat because it fits better (r = −0.83 vs −0.79) and because it
properly punishes a midfield stuffed with forwards. Correlations: back four **r = −0.748**,
GK overall **r = −0.798**. Keeper save % adds independent signal on the residual at
**r = −0.308** and is applied as a bonus/penalty on top.

**Out-of-position attacking output** blends a player's own rate into the *position's* rate
as he is moved (`naturalness = 1 + penalty/25`, clamped 0..1). A striker at centre-back does
not take his shots with him; he inherits the handful a centre-back gets, scaled by his
quality. This is what stops ten drafted strikers scoring five a game.

Measured share of a team's npxG by position:
`ST 39.2% · Winger 21.4% · CAM 17.9% · CM 8.1% · CB 5.2% · DM 4.4% · FB 3.7%`

**Scoreline** is a Poisson draw:
`λ_home = homeAttack × (awayDefence / 1.3505) × 1.2452`

Home advantage **1.2452** was measured over the 3,800 real fixtures — not the folklore 1.3.

### 6.2 Validation

`RealSeasonValidationTest` replays **156 real club-seasons** through the shipped Kotlin:

| Metric | Result |
|---|---|
| League points, correlation | **r = 0.916** |
| League points, RMSE | **7.1** over a 38-game season |
| Goals for | r = 0.864 |
| Goals against | r = 0.853 |
| Real champion in predicted top 4 | **6 of 6 seasons** |
| Bias | 52.9 predicted vs 53.0 actual |

Useful sanity reference — **mean XI rating by real finishing position**:

```
1st 85.5   2nd 84.1   3rd 83.0   4th 81.6   5th 80.9   6th 79.9
7th 79.7   8th 79.1   9th 78.3  10th 77.9  …  20th 74.8
```

An 82-rated XI finishing 4th is correct, not generous.

### 6.3 Only two numbers are judgement, not measurement

`ATTACK_SATURATION_ASYMPTOTE = 3.30` (no data exists above 3.00) and
`POSITION_TRANSFER_RANGE = 25.0`. Both say so in the source. Everything else has a
correlation attached.

### 6.4 Seeding

Run seeds are sequential ids, and Kotlin's generator correlates across neighbouring seeds.
Feeding them in raw skewed the takeover draw to 35.5% / 33.3% / 31.2% instead of an even
third — about 3.6σ, invisible in play. All seeds now pass through a SplitMix64 finalizer
(`sim/Seeding.kt`).

---

## 7. The season

### 7.1 The takeover draw

The XI replaces one of the season's **three real relegated clubs** (18th/19th/20th that
year, from the real table). The choice is exactly 1-in-3 but is *arrived at* through **300
successive draws** with a tally the user watches climb, a lead that changes hands, and a
sudden-death tie-break (needed in ~4% of draws). Ties are never broken by list order —
that would hand the place to whoever the database returned first.

### 7.2 The counterfactual season

`SeasonSimulator.playCounterfactual` takes the season's real 380 fixtures, hands the
replaced club's 38 to the user's XI **keeping matchday and venue**, plays only those, and
leaves the other 342 at their real scorelines.

Consequences, all test-asserted:
- Every non-user match keeps its exact historical result.
- The user inherits the replaced club's exact fixture list.
- Each club's points equal *its real points from the other 342 matches, plus whatever it
  takes off the user*. So a side that took 6 points off the relegated club but none off the
  user drops more than one place; one that lost to them but beats the user climbs.
- A weak XI leaves the real champion champion.

### 7.3 Match reports

Scorers, assists and cards are attributed **from** the scoreline, never producing it — so
real historical matches get scorers too and the league-wide top-scorer list is complete.

**The attribution is deliberately asymmetric:**

- **Real clubs** — weighted by whole-squad **season totals**. A season total already
  contains the games a player missed.
- **The user's XI** — **per-90 rates over 38 full matches**, because a drafted XI has no
  bench, no rotation and no injuries.

This asymmetry fixed a real bug. Per-90 weighting over an eleven-man pool gave Isak 32
goals (real 21), Chris Wood 30 (real 14), Muniz 30 (real 9), Trippier 21 assists (real 10).
Two compounding causes, both measured: a top-eleven player plays only **69.6%** of a season,
and a top eleven scores only **68.9%** of its club's goals.

**Cards are the exception to the per-90 rule.** Yellows scale as **minutes^0.764**, not
linearly — mean yellows per 90 *falls* as a player features more (0.235 in the 450–900
minute band down to 0.151 above 2,900) because a booked player is substituted and one on a
yellow is managed carefully. Goals show no such decline, so the correction applies to cards
only. Projecting linearly put Modrić on 15 yellows off a real 7 in 1,744 minutes.

> Methodological note worth carrying: that exponent must be derived from **band rates**, not
> a log-log regression on individual players. The regression conditions on a non-zero count,
> so it only sees players who were booked at all — it returns 0.612 for yellows and a
> spurious 0.565 for *goals*, which the unbiased band means show to be linear.

Other measured event constants: **70.4%** of goals carry an assist; teams average **2.129**
yellows and **0.0932** reds per match; the median club's leading scorer takes **35.2%** of
its goals (simulated: 35.1%); simulated Golden Boots land 19–28 against a real 23–36.

### 7.4 Season presentation

- Matchday by matchday, or **"play the rest"** at 2,200 ms a week with **Stop** and **Skip
  to the end**, resuming from wherever the user is.
- Each week the screen scrolls to the **match card**, not the table.
- The user's match is shown large with a win/draw/loss border and a two-column scoresheet
  (⚽ scorer + minute, assist beneath, 🟨/🟥 bookings); other results listed compactly.
- **League table**: Club, GP, W, D, L, GF, GA, GD, Pts. Nine columns need ~390dp and a phone
  gives 360, so the identity block is pinned and the numeric block scrolls horizontally on
  **one shared scroll state** across the header and every row.
- **Statistics on a separate screen**, reached by a button beside Done. The user's XI leads
  (top scorers, assists, involvements, keeper's clean sheets, bookings — five each, with
  per-match rates), then the league's top ten in each category. Done asks "Leave without the
  statistics?" because the run is not saved.

---

## 8. Screen inventory

| Screen | State |
|---|---|
| Setup | **Built** — league, formation, rerolls; entry to both modes |
| Draft | **Built** — spin, reveal, pitch placement, View Team / Final Edit, quit guard |
| Free Mode | **Built** — position → club × season → squad |
| Season | **Built** — takeover draw, matchday play, live table |
| Statistics | **Built** — user XI + league leaderboards |
| Onboarding, Home, History, Profile, Finalize, Simulating, Results | **Placeholders** |
| DesignSystem | Living component reference (remove before ship) |

**Design system:** dark, near-black surfaces (`#0C0D0F`) with a single gold accent
(`#F5C518`); flat/solid components — a glossy, gradient-heavy skeuomorphic pass was
explicitly rejected. 53 club crests bundled as drawables with a generated colour+initials
fallback. 19 real-world formations, grouped by defenders at the back.

---

## 9. ETL pipeline

27 Python scripts in `etl/` (offline, never shipped). Highlights:

- `fifa_ratings.py` / `fifa_lookup.py` — EA editions 20–24, re-keyed by edition, with the
  season→edition fallback chains and the tier-cascade matcher.
- `understat.py` — xG/xA/npxG for all ten league-seasons (the response is gzipped and the
  player array is top-level, both of which look like a block at first).
- `fbref_archive.py` — recovers pre-strip FBref data from the worldfootballR `.rds` mirror.
- `sim_calibration.py`, `sim_position_shares.py`, `sim_fit.py`,
  `sim_events_calibration.py` — every engine constant.
- `card_scaling.py`, `defence_check.py`, `spin_repeat_weights.py`, `draft_sim.py` — the
  measurement work behind specific decisions.
- `export_sim_fixture.py` — writes `real_squads.tsv`, the 156-club-season test fixture.

Migrations are applied **manually by the user** in the Supabase SQL editor, never by tooling.
Current: `schema.sql` + migrations 0002–0006 (random-season draft, rerolls, spin caps,
position ratings, rich player stats).

---

## 10. Testing

**85 unit tests, all passing.** No instrumentation or UI tests.

| Suite | Tests | Covers |
|---|---|---|
| `MatchEngineTest` | 13 | real title/relegation squads, ten strikers, saturation, determinism, upsets |
| `SeasonStatsTest` | 14 | goal credit, assists, golden boot range, scorer share, cards, per-team lists |
| `SeasonSimulatorTest` | 10 | fixture list correctness, table arithmetic, sorting, replay |
| `CounterfactualSeasonTest` | 9 | history preserved, inherited fixtures, points arithmetic |
| `PositionRatingTest` | 8 | out-of-position deltas, GK boundary, wrong-flank |
| `CardProjectionTest` | 7 | the minutes^0.764 law and that it never touches goals |
| `TakeoverDrawTest` | 7 | uniformity over 6,000 draws, order-independence, sudden death |
| `SpinWeightingTest` | 6 | repeats stay possible but uncommon |
| `FormationTest`, `CrestSlugTest` | 7 | shape parsing, crest filename slugs |
| `RealSeasonValidationTest` | 3 | **the whole engine replayed over 156 real club-seasons** |
| `ExampleUnitTest` | 1 | template leftover |

Several tests assert *behavioural* properties rather than values — the best side wins
20–57 of 60 seasons; a club repeats in 35–75% of drafts — because collapsing to either
extreme would break the game without breaking a build.

---

## 11. Known gaps

1. **Nothing persists.** No auth, no `draft_runs`/`draft_picks`/`simulation_results` rows.
   `ActiveRun` is an in-memory singleton, so killing the app loses a run *mid-season*. This
   is the biggest gap. It also keeps **spin caps inert** — `draft_eligible_club_seasons`
   (migration 0004) only applies when the ViewModel has a `runId`, which needs auth.
2. **Two leagues, five seasons each.** Everything is built to scale but nothing else is
   loaded.
3. **No bench.** Eleven players play every minute, so the user's individual totals sit above
   a real player's — the team total is correct, it is simply concentrated over 11 players
   instead of ~20, giving the top scorer ~1.45× a real one's share.
4. **Defence cannot distinguish a Getafe.** Real sides at a weighted defensive rating of 80
   concede a median of 46 (model: 47) but range 34–63. The columns that would explain
   defensive organisation are exactly the ones FBref stripped.
5. Placeholder screens: Home, Onboarding, History, Profile.
6. ~~Match minutes are uniform across the 90~~ **FIXED 2026-09-21.** Goal minutes now come
   from `GoalMinutes.kt`, fitted by `etl/goal_minutes.py` on 1,084 real World Cup goal
   minutes: a linear in-play tilt (closing quarter-hour ≈ 1.4× the opening one) plus the
   two whistle minutes pinned to what was really scored in them (minute 90 alone holds
   9.8% of all goals, against 1.1% under a flat draw). The shape is stable across the six
   editions (permutation p = 0.18) and between group and knockout football (p = 0.76);
   worst error across the six 15-minute bands is 1.0 points. The remaining assumption is
   that club football shares the shape — no club season in the database records a minute,
   so nothing can test it. **Bookings are still uniform**: the scrape carries goals only.
   Added time is carried on the event rather than rounded away (`90+4'`), and goals in one
   match must fall `GoalMinutes.MIN_SEPARATION` = 2 minutes apart — drawn independently
   they shared a minute in 1.1% of pairs, which has never happened once in 1,481 real
   pairs, and clustered within two minutes at 5.2% against a real 1.8%.
7. Wing-backs are priced off a measured per-role offset (`etl/wing_back_cost.py` →
   `sim/WingBackCost.kt`), because the stored grid keeps one full-back column, max(lb, rb),
   and drops EA's `lwb`/`rwb` entirely. The exact fix is a stored `pos_rating_wb`, which
   needs a migration and a reload of every season; the offset is worth about a point
   (within-role sd 0.86–1.35).

---

## 12. Things already tried and rejected — please don't re-propose without new evidence

| Idea | Why it was dropped |
|---|---|
| Attributes from box-score stats | Defensive volume is inverted; Kurt Zouma scored 98 finishing off 5 goals |
| Squad-strength weighting on spins | Measured: compressed XI spread 3.7→2.1, made drafts *more* identical |
| Banning repeat clubs outright | Overshot — turned "uncommon" into "impossible" |
| Re-simulating all 380 matches | Rewrote history (Barcelona winning 2021/22) |
| API-Football for the missing columns | No clearances, no aerials, at any tier |
| Locked positions by role | 22% of squads have no CAM; drafts failed to complete in 2–6% of runs |

---

## 13. Open questions — where a second opinion would help most

1. **Squad depth.** Should the user draft 3–5 substitutes so rotation is real and individual
   stats normalise on their own? It lengthens the draft and dilutes "your XI". The
   alternative currently shipped is context rather than correction (per-match rates beside
   totals). *This is the live decision.*
2. **Progression.** There is no meta-game: no history, no unlocks, no reason to play a
   second run beyond curiosity. What is the lightest thing that would give runs continuity?
3. **Difficulty.** The draft reliably yields 82–83 OVR and rerolls are worth +0.4 each. Is
   there a lever that creates *variance between runs* without the compression that squad
   weighting caused?
4. **The counterfactual's edges.** The replaced club vanishes from the league entirely. Is
   that right, or should they be shown somewhere (e.g. "you replaced Cádiz, who really
   finished 19th")?
5. **Scale vs depth.** Two leagues × five seasons is thin for cross-era mixing. Is it worth
   loading more seasons with *fewer* columns, given the engine only reads what every season
   has?
6. **Multiplayer / sharing.** Nothing exists. Is a shareable season result the obvious
   first social feature, or a distraction?
7. **Anything the engine is missing that is measurable from the columns listed in §3.2.**
   That constraint is firm — a suggestion needing clearances or aerials cannot be built.
