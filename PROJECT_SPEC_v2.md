# Project: "Dream XI" — Historical Football Draft & Simulation App
### Android Native App — Full Ground-Up Build Spec (v2 — Backend-Connected)

> **This supersedes PROJECT_SPEC.md.** Key changes from v1: the app is no longer
> offline-first. We now use a hosted backend (Supabase, or equivalent) for auth,
> the entire player/stats dataset, and user draft history — with local caching for
> speed and offline resilience. A full UI/UX design system section has been added;
> visual polish is a first-class requirement, not a "phase 2 nice-to-have."

---

## 1. Vision & Elevator Pitch

A single-player Android app where the user drafts a fictional all-time XI by randomly
"spinning" onto real club-seasons from top European football leagues (2000–2025) and
picking real players from those squads, position by position. Each player's in-game
attributes are **derived from their actual statistical performance in that specific
season** — not a fixed/generic rating. Once the draft is complete, the user's XI is
run through a season simulation engine against a real historical league and given a
final league position, points total, and a tactical "team report."

Users have accounts. Their draft history is saved server-side and viewable across
sessions/devices. No multiplayer or leaderboards for now — this is single-player
gameplay with cloud-backed persistence, not a social/competitive product.

The app must feel like a **premium, polished sports product** — think the visual
bar of FIFA Ultimate Team card reveals, Fantasy Premier League's clean data
presentation, or a slick stats/analytics app — not a functional prototype. See §10
for the full design system spec.

---

## 2. Core Game Loop (RESOLVED 2026-08-28 — replaces v1's single-season framing)

The defining mechanic: **every draft round independently spins a random
season**, not just a random club within one season chosen up front. A
single draft can hand you 2011 Real Madrid in round 1 and 2022 Sevilla in
round 4 — that cross-era mixing is the actual point of the game, not an
edge case.

1. **Setup screen** — choose **League** (Premier League or La Liga for MVP),
   choose formation on a visual pitch layout, "Start Run". No year/season
   choice here — see step 2.
2. **Draft screen** — round-by-round: SPIN → randomly picks **both** a season
   (from every season of the chosen league we have full data for) **and** a
   club within it → reveals that real club-season with its real record
   (position, W-D-L, GF/GA, Pts) → shows eligible player pool for the current
   empty slot's position, drawn from that club-season's real squad → user
   filters (GK/DEF/MID/FWD, or the finer 8-role tags — see
   ATTRIBUTE_FORMULA_SPEC.md §1.2), inspects player cards (overall rating,
   six sub-attributes, trait chips, real-stat blurb, season label), picks one
   → repeat until all slots filled. Limited rerolls per draft.
   - **Player identity is unique across the whole draft, not per pick.** A
     real person, once drafted in any season, must not appear in any later
     round's eligible pool under a different season/club — enforce this by
     excluding already-picked `player_id`s (not `player_season_stat_id`s)
     from every subsequent spin's pool.
3. **Finalize screen** — full XI on pitch (each player shown with their
   season label, e.g. "Messi — Barcelona '09"), last position swaps,
   "Simulate Season"
4. **Simulation** — only now does a specific season get chosen: the system
   randomly picks **any one season of the chosen league** we have full data
   for (independent of which seasons the drafted players actually came
   from), then randomly picks **one of that season's real bottom-3 clubs**
   (18th/19th/20th — the actual relegation zone, not always dead-last) for
   the XI to replace. The XI then plays that club's real 38-fixture home/away
   schedule against the other 19 real clubs from that season. See §8.1.
5. **Results/Report screen** — final position/points/record, radar chart of
   XI attributes vs. that (randomly chosen) season's real champion,
   aggregated trait tags, tactical write-up
6. **History screen** — all past draft runs and results, pulled from the
   user's account, browsable/re-viewable from any device they log into

---

### 2.1 Free Mode (REQUESTED 2026-08-30 — to be specified in detail, then built)

A second, unrestricted way to assemble an XI, alongside the spin draft rather
than replacing it.

1. Pick a **league**.
2. Tap a **position** on the pitch.
3. Two dropdowns — **club** × **season of that club** — open that squad; take
   whoever you want.
4. Repeat for all eleven, then simulate exactly as a drafted XI does: takeover
   draw, counterfactual season, match reports, season statistics. One engine,
   one set of season screens, no duplication.

**This mode is explicitly for fun, and it is meant to be easy.** In the user's
words it "guarantees 90 OVR squads easily". That is the intent: do NOT add
rating caps, budgets, balancing or squad-strength weighting. Scarcity is what
the spin draft is for; the absence of it is what this mode is for.

Reusable unchanged from the draft: formation lock, out-of-position pricing off
the EA positional grid, the goalkeeper boundary, and player uniqueness (one real
person once per XI).

Open: the user wants the player-picking flow "simplified" for this mode and will
describe how. Get that before building.

## 3. Explicit Design Differences From the Reference App

- **No public leaderboard / no comparison against other users.** Comparisons are
  against real historical teams/seasons only (see §8.3).
- **User accounts required** (or at least strongly encouraged) since history is
  cloud-saved — see §5 for auth approach and whether guest/local-only mode is
  offered.
- Attribute values are **season-specific and dynamically computed from real
  stats** — same player drafted in different seasons (or different draft runs)
  shows different attributes reflecting that season's actual performance.
- Visual/UX bar is intentionally higher than a typical indie/hobby project — see
  §10.

---

## 4. Target Scope (Leagues & Seasons)

### Phase 1 (MVP — build this first, fully polished, end-to-end)
- **Premier League**, 2000/01 – 2024/25
- **La Liga**, 2000/01 – 2024/25

### Phase 2 (expand data pipeline)
- Serie A, Bundesliga, Ligue 1

### Data confidence tiers (drives attribute-formula fallback logic, §7)
- **Tier A (rich):** ~2017–2025 — advanced per-90 stats (xG, xA, progressive
  actions, tackles, aerial win%, pass completion, etc.)
- **Tier B (moderate):** ~2010–2017 — solid basic stats, partial advanced stats
- **Tier C (basic):** ~2000–2010 — goals/assists/apps/minutes/cards + team-level
  context stats as proxies for individual defensive/GK attributes

Attribute formulas must degrade gracefully across tiers — always fall back to the
best available stat, never leave an attribute blank or crash.

> **2026-01-20 update:** FBref's advanced-stats provider terminated their
> license and the data was deleted site-wide, so the "Tier A (rich)" band
> above (xG, xA, progressive actions) no longer exists as a free option from
> any source, including a paid Stathead subscription. See
> `ATTRIBUTE_FORMULA_SPEC.md` §0 for the actual field-by-field availability
> this project is now designed around.

---

## 5. Backend Architecture (Supabase-based)

### 5.1 Why Supabase (or equivalent — flag as a decision, not locked-in)
- Managed Postgres (real relational DB — good fit for our fairly relational
  schema: leagues → seasons → clubs → players → stats)
- Built-in Auth (email/password, magic link, and/or OAuth — Google Sign-In is the
  natural choice for an Android app)
- Row Level Security (RLS) — lets us cleanly restrict user-generated tables
  (draft runs, sim results) so each user only reads/writes their own rows, while
  reference data (players, stats, leagues) is public-read
- Auto-generated REST + realtime APIs, plus official Kotlin/Android client
  libraries — reduces custom backend code to near-zero for CRUD operations
- Free tier is generous enough for early development and small-scale usage

Alternatives worth a glance if Supabase doesn't fit later: Firebase (Firestore +
Auth — NoSQL, less natural fit for our relational stats data, but excellent
Android SDK support and realtime sync), or a custom backend (Ktor/Ktor+Postgres,
or Node/Express) if you outgrow managed platforms. Recommend **starting with
Supabase** given the relational data shape and lower setup overhead.

### 5.2 What lives where

| Data | Location | Notes |
|---|---|---|
| Leagues, Seasons, Clubs, Players, Player-Season Stats, Computed Attributes | Supabase Postgres (public read) | The "reference dataset" — populated once via your data pipeline (§6), read-only from the app's perspective |
| User accounts | Supabase Auth | Email/password + Google Sign-In recommended |
| Draft Runs, Draft Picks, Simulation Results | Supabase Postgres (RLS: user can only access own rows) | User-generated, synced across devices |
| Local cache | Room (Android) | Cache of reference data + user's own recent runs, for offline viewing and snappy UI — see §5.3 |

### 5.3 Offline behavior (since "always online" is assumed but shouldn't be a hard
requirement for a good experience)
- Cache reference data (players/stats for leagues actively being browsed/drafted)
  locally in Room after first fetch, so re-opening the app or drafting again
  doesn't refetch everything from scratch every time.
- Cache the user's own draft history locally too, so the History screen loads
  instantly and works offline for previously-viewed data; sync/refresh from
  Supabase when connectivity is available.
- New drafts/simulations themselves reasonably require connectivity (to fetch
  fresh player pools and write results back) — this is fine per your instruction
  that "everyone has internet," but the app should still fail gracefully (clear
  error state, retry option) rather than crash if connectivity drops mid-draft.

### 5.4 Auth flow
- **RESOLVED:** allow a **guest draft locally first** — the user can complete a
  full draft + simulation with no account, then is prompted to sign up
  (email+password and/or Google Sign-In) to save it. Requires local run state
  that can be migrated/attached to a `user_id` once the account is created,
  rather than assuming every draft starts with an authenticated session.
- Google Sign-In should be implemented via the modern **Credential Manager API**
  (Sign in with Google), not the older/deprecated `GoogleSignInClient` flow.
- Supabase Auth session tokens stored securely (Android Keystore-backed encrypted
  storage, not raw SharedPreferences) and refreshed automatically via the
  Supabase Kotlin client's session management.

---

## 6. Data Pipeline (population of the Supabase reference dataset)

This is unchanged in substance from v1, just targeting Postgres instead of a
bundled local DB.

### 6.1 Sources to evaluate/scrape (offline ETL process, not runtime)
- FBref (Sports Reference) — best source for Tier A/B stats
- Transfermarkt — squad lists, appearances, goals
- Official league sites / Wikipedia season pages — validate league tables and
  fixture lists

### 6.2 Data needed per league/season
1. Final league table (club, position, W-D-L, GF, GA, Pts)
2. **Real fixture list** (who played whom, home/away, in order) — **RESOLVED**
   (see §8.1/§12): the drafted XI replaces the real 20th-place (last-place)
   club for that season and plays that club's actual 38-fixture schedule (19
   opponents, home and away) against the other 19 real clubs. No synthetic
   round-robin. This means the ETL must scrape/validate full fixture-by-fixture
   results, not just final tables — heavier scope than a table-only pull.
3. Full squad list per club/season (players with meaningful minutes/appearances)
4. Per-player per-season stat line (fields vary by tier — see §4)

### 6.3 Import process
- Build the ETL as an **offline script** (Python is a natural fit — pandas for
  cleaning, then bulk-insert into Supabase via its REST API or direct Postgres
  connection). This runs on your machine / a CI job, not on-device.
- Compute derived attributes (§7) either at import time (store the final 0–99
  attribute values in Postgres so the app never has to compute them) — **strongly
  recommended**, since it keeps the app itself simple/fast and lets you re-run
  the formula and re-import if you tune it later, rather than shipping formula
  logic to every device.
- Legal/ethical note unchanged from v1: check each source's ToS before scraping/
  redistributing — outside what I can fully resolve for you.

---

## 7. Attribute & Rating System

(Unchanged in substance from v1 — repeated here for completeness since this is
now the authoritative doc.)

### 7.1 Six core attributes
Finishing, Creation, Carrying, Build-up, Defense, Physical (0–99 each), plus an
overall 0–99 rating and rule-based trait tags (Playmaker, Pacy, Aerial threat,
Press-resistant, etc.)

### 7.2 Formula design principles
- Normalize **within-season, within-league, within-position-group** (percentile/
  z-score against peers that season), not against an absolute all-time scale
- Position-group-specific weighting (GK, CB, FB, DM, CM, CAM, Winger, ST)
- Graceful degradation by data tier — core stats always available, bonus stats
  refine further when present
- Traits as transparent, tunable, threshold-based tags off underlying stats

### 7.3 Next step — DONE, see ATTRIBUTE_FORMULA_SPEC.md
The literal stat → 0–99 mapping is now written up as its own structured
reference: **`ATTRIBUTE_FORMULA_SPEC.md`** at the repo root. Built after the
§13 Milestone 2 pilot (2023/24 Premier League) once the real data landscape
was known — note that FBref's advanced-stats feed (xG, xA, progressive
actions, touches) was permanently deleted site-wide in January 2026, well
after this spec's §4 tier assumptions were written, so the formula doc
documents a real, narrower data reality rather than the original Tier A
ambition. Also introduces an 8-group role-tagging system (GK/CB/FB/DM/CM/
CAM/Winger/ST, sourced from Transfermarkt) for draft slot eligibility,
separate from the coarser 4-group system used for attribute percentile
normalization — see that doc's §1 for why these are deliberately different.

---

## 8. Simulation Engine

**STATUS 2026-08-30: BUILT, CALIBRATED AND ON-DEVICE.** 71 unit tests passing.
Code in `app/src/main/java/com/dreamxi/app/sim/` (pure Kotlin, no Android
imports, so it lifts into the Ktor service unchanged). Calibration scripts in
`etl/sim_*.py`. The subsections below are the original design; what was actually
built follows it in §8.5, and where the two disagree, §8.5 is authoritative.

### 8.1 v1 approach: Poisson-based match simulation
- Compute team Attack Strength / Defense Strength from the drafted XI's
  aggregated, position-weighted attributes
- Per fixture: expected goals (λ) per side from own Attack vs. opponent Defense
  (real historical opponents from that season's table), sample actual goals from
  a Poisson distribution
- Aggregate across full season length into final points, W-D-L, GF, GA, table
  position
- **RESOLVED (updated 2026-08-28 — see §2):** which season gets simulated is
  decided at simulate time, not draft time — a random season of the chosen
  league (among those we have full data for) is picked once the draft is
  complete, independent of which seasons the drafted players came from. The
  drafted XI then **replaces a randomly chosen one of that season's real
  bottom-3 clubs** (18th/19th/20th — the actual relegation zone that year,
  not always dead-last), playing that club's actual 38-game fixture schedule
  (home and away vs. the other 19 real clubs). The final table is a full
  20-team table, regenerated from real results for the 19 other clubs plus
  the user's simulated results in the replaced club's fixtures. This
  requires real fixture-by-fixture data per season, not just the final table
  (see §6.2).

### 8.2 v2+ enhancements (not MVP)
Home/away splits, form/fatigue, possession-adjusted event simulation, narrative
match commentary — evaluate after v1 feels fun and fair.

### 8.3 Comparison baseline
Compare "Your XI" against the **real champion / real table** of that season
(already implied by the reference app's radar chart, e.g. "Your XI vs. '01 ARS
(1st)"). No cross-user percentile needed since there's no leaderboard.

### 8.4 Where does simulation run?
**RESOLVED:** runs server-side, as a **small dedicated Kotlin/Ktor service**
(not a Supabase Edge Function). Reasoning:
- Keeps the entire stack in one language — the sim service can share data
  classes/DTOs with the Android app (via a shared Gradle module or a small
  published library) and be tested with the same JUnit suite, instead of
  re-implementing the same logic/shapes in Deno/TypeScript.
- Still gets all the server-side benefits: single source of truth for sim
  logic, tunable/fixable without an app release, keeps the "black box" fair
  and consistent, avoids exposing the algorithm client-side.
- Needs its own light hosting (Fly.io, Railway, or Cloud Run are all
  reasonable free/cheap-tier options) and a simple Retrofit/Ktor client call
  from the app — slightly more infra than an Edge Function, but low ongoing
  maintenance for a function this small and well-defined.
- Input: drafted XI + season context (including which club it replaces and
  that club's real fixture list). Output: match-by-match/aggregated result +
  report data, written back to `simulation_results` in Supabase.

---

### 8.5 What was actually built (authoritative)

**The rule for the sim package: no constant that was not measured.** Every value
in `SimModel.kt` is fitted against the 200 real club-seasons and carries its fit
quality beside it.

- **Attack is additive** — an XI's summed npxG/90 predicts real goals at
  r = +0.864. Saturates above 3.00 npxG (Man City 2019/20, the highest real XI).
- **Defence is priced on RATING, never on defensive volume.** Tackles,
  interceptions and clearances correlate the WRONG WAY with defensive quality.
  Back four r = −0.748, GK OVR r = −0.798, position-weighted; keeper save% adds
  independent signal at r = −0.308 on the residual.
- **Out-of-position play** blends a player's own rate into the POSITION's rate
  as he is moved, anchored on EA's per-player positional grid.
- Poisson scoreline; home advantage **1.2452**, measured over 3,800 fixtures.
- **Validated** by replaying 156 real club-seasons through the shipped Kotlin:
  league points r = 0.916, RMSE 7.1; real champion in the predicted top four in
  6 of 6 seasons. Reference for judging a reported result — mean XI rating by
  real finishing position: 1st 85.5, 4th 81.6, 8th 79.1, 20th 74.8.

**§8.1's "re-simulate the whole league" was replaced by a COUNTERFACTUAL
season** (2026-08-30). The season's real 380 fixtures are loaded from the
`fixtures` table; the replaced club's 38 are handed to the user's XI keeping
matchday and venue; the other 342 keep their real scorelines. Real Madrid still
wins 2021/22 unless the user takes points off them. A club's record therefore
changes only through its two matches against the user. Do not revert to
simulating all 380 — the question is "could my eleven have done it?", which only
means something against the season that really happened.

**Match reports** (scorers, assists, cards) are attributed from the scoreline
rather than producing it, so real matches get scorers too and the top-scorer
list covers the whole league. The attribution is deliberately asymmetric: real
clubs are weighted by whole-squad SEASON TOTALS (which already contain missed
games), the user's XI by PER-90 RATES over 38 full matches (it has no bench,
rotation or injuries). Getting this wrong gave Isak 32 goals against a real 21.

**Two numbers are judgement, not measurement**, and both say so in the source:
`ATTACK_SATURATION_ASYMPTOTE = 3.30` and `POSITION_TRANSFER_RANGE = 25.0`.

**Seeds must be scrambled through `sim/Seeding.kt`.** Run ids are sequential and
Kotlin's generator correlates across neighbouring seeds; feeding them in raw
measurably skewed the takeover draw.

## 9. Data Schema (Postgres / Supabase)

```sql
-- Reference data (public read, populated by ETL pipeline)

leagues (
  id, name, country, default_tier
)

seasons (
  id, league_id FK, label, start_year, data_tier
)

club_seasons (
  id, season_id FK, club_name, final_position,
  wins, draws, losses, goals_for, goals_against, points
)

-- Real fixture-by-fixture results (added post-v2: the drafted XI replaces the
-- real last-place club and plays that club's actual 38-game schedule, §8.1 —
-- this needs real fixtures, not just the final table)
fixtures (
  id, season_id FK, matchday,
  home_club_season_id FK, away_club_season_id FK,
  home_goals, away_goals
)

players (
  id, full_name, nationality, date_of_birth
)

player_season_stats (
  id, player_id FK, club_season_id FK,
  primary_position, secondary_positions,
  appearances, minutes, goals, assists, yellow_cards, red_cards,
  shots, shots_on_target, key_passes, pass_completion_pct,          -- Tier B/A
  tackles, interceptions, clearances, aerial_won, aerial_lost,      -- Tier B/A
  dribbles_completed, saves, save_pct, clean_sheets,                -- Tier B/A, GK/DEF
  xg, xa, progressive_carries, progressive_passes,                  -- Tier A
  -- computed at ETL time:
  finishing, creation, carrying, buildup, defense, physical,        -- 0-99
  overall_rating,                                                   -- 0-99
  traits                                                             -- text[] or jsonb
)

-- User-generated data (RLS: owner-only read/write)

profiles (
  id (matches auth.users.id), display_name, created_at
)

draft_runs (
  id, user_id FK, league_id FK, formation, created_at
  -- no season_scope / replaced_club_season_id here — each draft round spins
  -- its own random season (§2), so no single season is tied to a draft run;
  -- which season+club to simulate against is decided at simulate time, below.
)

draft_picks (
  id, run_id FK, slot_position, player_season_stat_id FK, round_number
)

simulation_results (
  id, run_id FK, simulated_season_id FK,
  replaced_club_season_id FK,  -- randomly chosen from that season's real bottom-3 (§8.1)
  final_position, points, wins, draws, losses, gf, ga,
  report_json, created_at
)
```

RLS policy summary:
- `leagues`, `seasons`, `club_seasons`, `fixtures`, `players`,
  `player_season_stats`: public `SELECT`, no client `INSERT`/`UPDATE`/`DELETE`
  (ETL writes via service role key)
- `profiles`, `draft_runs`, `draft_picks`, `simulation_results`: `SELECT`/
  `INSERT`/`UPDATE`/`DELETE` restricted to rows where `user_id = auth.uid()`

---

## 10. UI/UX Design System — "No Shortcuts" Spec

This section exists because "beautiful" is subjective unless we define it. Treat
this as the visual/interaction contract for every screen.

### 10.1 Design direction & mood
- **Reference mood:** premium sports-tech — think FIFA Ultimate Team pack
  openings, a well-funded fantasy football app's stats dashboards, or a modern
  broadcast graphics package. Dark-mode-first (matches the reference screenshots'
  near-black background with a bold gold/yellow accent), high contrast, confident
  typography, generous use of motion for feedback and delight.
- **Not** a generic Material Design 3 default look. Custom theme, custom
  component shapes, deliberate color/type choices — Compose gives you full
  control, use it.

### 10.2 Color system
- Deep near-black base (`#0C0D0F`-ish, matches reference) rather than pure
  black — pure black can look flat/cheap on OLED vs. a slightly warm/cool
  near-black with subtle elevation via lighter surface shades
- One confident accent color (gold/amber, as in the reference) used sparingly
  and consistently for primary actions (Spin, Start Run, Simulate) and key
  highlights (badges, active states) — avoid decorating everything in it
- Position-based accent colors for player cards/tags (e.g., GK = blue, DEF =
  gold/tan, MID = green, FWD = red/orange) — matches the reference screenshots
  and gives instant visual position recognition
- Clear semantic colors for win/draw/loss and rating tiers (e.g., a rating badge
  color scale from grey → green → gold as overall rating increases)
- Define this as an actual Compose `ColorScheme`/custom theme object, not ad hoc
  hex values scattered through composables

### 10.3 Typography
- A confident, slightly condensed display typeface for headlines/scores/big
  numbers (results screen "76 PTS", rating badges) — something with sports
  broadcast energy, not a default system font
- A clean, highly legible body/UI typeface for stats, labels, lists
- Establish a clear type scale (display / headline / title / body / label /
  caption) as a Compose `Typography` object used consistently everywhere —
  no ad hoc font sizes per screen

### 10.4 Motion & feedback (this is where "beautiful" mostly lives)
- **Spin action:** should feel like an actual event — a wheel/reveal animation,
  suspense beat, then the club-season card animating in with some weight (scale/
  fade/slide combo, not an instant swap)
- **Player card reveal/selection:** satisfying press feedback (scale-down on
  press, subtle glow/border highlight on selection), smooth insertion into the
  pitch slot with a coordinated transition (shared-element-style animation from
  card → pitch position, using Compose's shared element / `LookaheadScope` APIs
  where practical)
- **Pitch view:** subtle ambient detail (turf texture/gradient, soft vignette)
  rather than a flat solid rectangle — small details like this separate "polished"
  from "functional"
- **Results reveal:** this is the emotional payoff screen — treat it like a
  proper "pack opening" moment. Sequenced reveal (final position → points →
  record → full XI list → radar chart), not everything appearing at once.
  Consider a subtle particle/confetti or light-sweep effect on a strong result.
- **Radar chart:** animate the polygon drawing in (grow from center outward),
  don't just statically render it
- **Screen transitions:** consistent, purposeful navigation transitions (shared
  axis / fade-through per Material Motion patterns, or fully custom) — never
  the default abrupt activity/fragment jump-cut feel
- **Micro-interactions everywhere:** button press states, loading skeletons
  (not bare spinners) while fetching from Supabase, pull-to-refresh on history,
  haptic feedback on key actions (spin result, pick confirmation, simulate)

### 10.5 Component inventory (build these as a proper shared component library,
not one-off per screen)
- Formation/pitch view (reusable across setup, draft, finalize, results)
- Player card (multiple density variants: full detail for draft picking, compact
  for pitch slots, minimal for history lists)
- Club-season summary card ("Team Spun")
- Rating badge (colored by tier)
- Trait chip
- Radar/spider chart (custom Canvas-drawn, animatable)
- Primary/secondary button styles matching the accent system
- Empty states, loading states, and error states — designed with the same care
  as the "happy path" screens, not an afterthought

### 10.6 Screen inventory (confirm nothing is missing)
1. Onboarding / Auth (sign up, log in, optional guest entry)
2. Home (entry point — start new draft, resume/continue, jump to history)
3. Setup (sim year + formation)
4. Draft (spin → pick, repeated)
5. Finalize (swap + confirm)
6. Simulating (a proper loading/anticipation screen, not a bare spinner —
   this is a moment to build tension given the payoff coming next)
7. Results/Report (scoreboard, XI list, radar chart, traits, tactical write-up)
8. History (list of past runs, tap into any for its full results screen again)
9. Profile/Settings (account info, log out, maybe theme/accessibility options)

### 10.7 Accessibility (don't skip this in pursuit of "beautiful")
- Sufficient contrast ratios even within the dark, high-contrast accent theme
- Scalable text support, sensible touch target sizes
- Content descriptions for icon-only buttons and chart elements
- Motion: respect system "reduce motion" settings for users who have it enabled

---

## 11. Proposed Android Tech Stack (updated)

- **Language:** Kotlin
- **UI:** Jetpack Compose — this is non-negotiable given the animation/motion
  requirements in §10; Compose's animation APIs (`animateFloatAsState`,
  `AnimatedVisibility`, `LookaheadScope`/shared elements, Canvas for custom
  charts) are the right toolset for this level of polish
- **Architecture:** MVVM, ViewModel + StateFlow/Compose State, Repository
  pattern abstracting Supabase (remote) + Room (local cache) behind a single
  data interface per feature
- **Backend/data:** Supabase (Postgres + Auth), accessed via the official
  `supabase-kt` Kotlin multiplatform client library
- **Local cache:** Room, storing cached reference data + user's own recent runs
  for offline viewing/snappy re-loads
- **Dependency injection:** Hilt
- **Charts:** custom Canvas-drawn radar chart (needed for the specific animated
  hexagonal style in §10.4/10.5) — off-the-shelf chart libraries rarely support
  this exact shape well or animate nicely; budget real time for this component,
  it's a signature visual piece
- **Image/asset loading:** Coil (for any player photos/club crests if you decide
  to include them — flag as an open scope question, since real player photos/
  crests raise their own licensing considerations, similar to the data-sourcing
  legal note in §6)
- **Networking:** handled via the Supabase client; a simple Retrofit/Ktor
  client call to the standalone Kotlin/Ktor simulation service (§8.4)
- **Security:** Android Keystore-backed encrypted storage for auth session
  tokens, standard Supabase RLS enforcing data access rules server-side (never
  trust client-side checks alone for who can see/edit what)
- **Testing:** JUnit + Turbine for ViewModel/StateFlow logic, dedicated unit
  tests for the attribute-calculation logic and the Kotlin/Ktor simulation
  service given how central correctness there is to the game feeling fair,
  plus **Compose UI tests / Paparazzi screenshot tests** to catch visual and
  animation regressions given how much of the product's quality bar rides on
  motion (§10.4)
- **Crash reporting / analytics:** Firebase Crashlytics (or Sentry) — not
  optional for a polish-first product; you need visibility into animation/state
  crashes on real devices you don't control
- **Google Sign-In:** Credential Manager API (Sign in with Google), not the
  deprecated `GoogleSignInClient`

---

## 12. Decisions (Resolved 2026-08-28)

All open decisions from this section have been made:

1. **Monetization:** None for v1. Free, no ads, no IAP — focus effort on the
   core loop and polish.
2. **Guest mode vs. required login:** Guest draft allowed locally; prompt to
   create an account to save it afterward. See §5.4.
3. **Fixture realism:** Real historical fixture list (not synthetic
   round-robin). See §6.2, §8.1.
4. **Real team replacement:** The drafted XI replaces the real **20th-place
   (last-place)** club for that season and plays its actual 38-fixture
   schedule against the other 19 real clubs; the final table is regenerated
   with the XI's results in place of the replaced club's real results. See
   §8.1.
5. **Simulation logic location:** Server-side, as a standalone **Kotlin/Ktor
   service** (not a Supabase Edge Function — kept in one language with the
   rest of the stack). See §8.4.
6. **Player photos / club crests:** Generic icons/initials for v1 — no real
   player photos or crests, avoiding licensing risk entirely. Can revisit once
   the core product is proven.
7. **Save-history limits:** Unlimited runs per user for v1. No pruning/cap
   logic needed; Supabase Postgres storage cost isn't a real concern yet.
8. **Formation flexibility:** Fixed formation templates for v1 (4-4-2, 4-3-3,
   3-5-2, etc., chosen at Setup) — not full custom drag-and-drop.
9. **Trait system tuning:** Rule-based thresholds (not a weighted/statistical
   model) — transparent and tunable, consistent with the rest of §7's formula
   design principles.
10. **Backend platform:** Confirmed — Supabase (Postgres + Auth).

Also decided alongside the tech stack review (§11): add **Crashlytics/Sentry**
for crash reporting, use the **Credential Manager API** for Google Sign-In (not
the deprecated `GoogleSignInClient`), and add **Compose UI/Paparazzi screenshot
tests** given how much of the product's quality bar rides on animation.

---

## 13. Suggested Build Order / Milestones

1. **Supabase project setup**: schema (§9), RLS policies, Auth config (email +
   Google Sign-In)
2. **Data pipeline v0**: pilot slice (PL + La Liga, ~5 seasons, few clubs each)
   through the ETL into Supabase, to validate schema + attribute formulas early
3. **Attribute formula spec**: finalize stat → 0–99 mapping per position group
   per tier (§7.3) as its own reference doc
4. **Design system foundation**: Compose theme (color/type/shape), core
   component library (§10.5) built and visually approved *before* wiring full
   screens — get the "beautiful" foundation locked early, not bolted on late
5. **Auth + onboarding flow**
6. **Setup + Draft screen** (using pilot data), including the spin
   animation and player-card interactions — this is the core loop, get it
   feeling great before moving on
7. **Finalize screen**
8. **Simulation engine v1** (Kotlin/Ktor service per §8.4), using pilot data's
   real fixture lists
9. **Results/Report screen**, including the animated radar chart
10. **History screen**, synced from Supabase with local caching
11. **Full data backfill**: scale ETL to all 25 seasons × PL + La Liga
12. **Polish pass**: motion refinement, empty/error/loading states, accessibility
    check, performance pass (especially animation frame rates)
13. **Phase 2**: extend data pipeline to Serie A, Bundesliga, Ligue 1

---

## 14. Summary for Claude-in-Android-Studio

When working on this project, always keep in mind:
- This is a **backend-connected** app (Supabase: Postgres + Auth), not offline-
  first — but should still cache sensibly and fail gracefully without
  connectivity.
- Reference data (leagues/seasons/clubs/players/stats/attributes) is
  **public-read, ETL-populated** — the app never writes to it. User data
  (draft runs, picks, results, profile) is **owner-only via RLS**.
- Player attributes are **always season-specific and precomputed at ETL time**
  from real stats — never a fixed universal rating, and never computed live
  on-device.
- Data tier (A/B/C) varies by season/league; all attribute logic must degrade
  gracefully.
- Simulation logic runs **server-side**, as a standalone **Kotlin/Ktor
  service** (not a Supabase Edge Function) — kept in one language with the
  rest of the stack, sharing DTOs and test tooling with the Android app.
- **Visual/UX polish is a hard requirement, not a stretch goal** — build the
  Compose design system and shared component library (§10) deliberately and
  early, rather than styling screens ad hoc as they're built. Motion design
  (§10.4) is where most of the "premium feel" will actually come from — budget
  real effort there, especially for the spin/reveal and results-screen moments.
- MVP league scope: Premier League + La Liga, 2000–2025, architected to extend
  to Serie A, Bundesliga, Ligue 1 later.
- Tech stack: Kotlin, Jetpack Compose, MVVM, Supabase (Postgres+Auth), Room
  (cache), Hilt, standalone Kotlin/Ktor sim service, Crashlytics/Sentry.
- The drafted XI replaces the real last-place club and plays a real 38-fixture
  season against the other 19 real clubs (§8.1) — the ETL needs real
  fixture-by-fixture data, not just final tables.
- §12 decisions are all resolved as of 2026-08-28 — build against them as
  settled, don't re-litigate.
