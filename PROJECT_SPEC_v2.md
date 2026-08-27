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

## 2. Core Game Loop (unchanged from v1, confirmed from reference screenshots)

1. **Setup screen** — choose Sim Year (or Random), choose formation on a visual
   pitch layout, "Start Run"
2. **Draft screen** — round-by-round: SPIN → reveals a real club-season with its
   real record (position, W-D-L, GF/GA, Pts) → shows eligible player pool for the
   current empty slot's position → user filters (GK/DEF/MID/FWD), inspects player
   cards (overall rating, six sub-attributes, trait chips, real-stat blurb), picks
   one → repeat until all slots filled. Limited rerolls per draft.
3. **Finalize screen** — full XI on pitch, last position swaps, "Simulate Season"
4. **Simulation** — XI is simulated across a real historical league season length
   against real historical opponent strength
5. **Results/Report screen** — final position/points/record, radar chart of XI
   attributes vs. that season's real champion, aggregated trait tags, tactical
   write-up
6. **History screen** — all past draft runs and results, pulled from the user's
   account, browsable/re-viewable from any device they log into

---

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
- Sign up / log in screen (email+password and/or Google Sign-In) before or after
  a first "guest" draft — **decision point**: do you want to let users try one
  draft before requiring an account (better conversion/onboarding), or require
  login up front (simpler to build first)? Recommend allowing a **guest draft
  locally**, then prompting to create an account to save it — this is a common,
  effective onboarding pattern.
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
2. Fixture list (or a synthetic round-robin of correct length — decision point,
   see v1 §9, still open)
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

### 7.3 Next step
Define the literal stat → 0–99 mapping per position group per tier as its own
structured reference (spreadsheet or doc) before/alongside writing the Python
ETL's attribute-calculation step. This is the single most important "fairness/
correctness" piece of the whole app.

---

## 8. Simulation Engine

### 8.1 v1 approach: Poisson-based match simulation
- Compute team Attack Strength / Defense Strength from the drafted XI's
  aggregated, position-weighted attributes
- Per fixture: expected goals (λ) per side from own Attack vs. opponent Defense
  (real historical opponents from that season's table), sample actual goals from
  a Poisson distribution
- Aggregate across full season length into final points, W-D-L, GF, GA, table
  position
- **Decision point (carried over from v1):** does the drafted XI replace a real
  team 1:1 in real fixtures, or play a synthetic round-robin against all real
  teams as an extra entrant? Affects data needs and realism framing.

### 8.2 v2+ enhancements (not MVP)
Home/away splits, form/fatigue, possession-adjusted event simulation, narrative
match commentary — evaluate after v1 feels fun and fair.

### 8.3 Comparison baseline
Compare "Your XI" against the **real champion / real table** of that season
(already implied by the reference app's radar chart, e.g. "Your XI vs. '01 ARS
(1st)"). No cross-user percentile needed since there's no leaderboard.

### 8.4 Where does simulation run?
Given we now have a backend, consider running the simulation **server-side**
(e.g., a Supabase Edge Function, or a small dedicated backend function) rather
than on-device:
- Pros: single source of truth for sim logic, easy to tune/fix without an app
  update, keeps the "black box" fair and consistent, avoids exposing/duplicating
  the algorithm client-side
- Cons: requires connectivity for this step (acceptable per your note), adds a
  bit of backend complexity (writing a Supabase Edge Function in
  Deno/TypeScript, or a small separate serverless function)
- **Recommendation:** run it server-side. It's a small, well-defined function
  (input: XI + season context, output: result + report data) and keeps your
  Android codebase focused on UI/UX rather than game-logic duplication risk.

---

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
  id, user_id FK, league_id FK, season_scope, formation, created_at
)

draft_picks (
  id, run_id FK, slot_position, player_season_stat_id FK, round_number
)

simulation_results (
  id, run_id FK, simulated_season_id FK,
  final_position, points, wins, draws, losses, gf, ga,
  report_json, created_at
)
```

RLS policy summary:
- `leagues`, `seasons`, `club_seasons`, `players`, `player_season_stats`: public
  `SELECT`, no client `INSERT`/`UPDATE`/`DELETE` (ETL writes via service role key)
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
- **Networking:** handled via the Supabase client; if a separate Edge
  Function/serverless endpoint is used for simulation (§8.4), a simple
  Retrofit/Ktor client call to that function
- **Security:** Android Keystore-backed encrypted storage for auth session
  tokens, standard Supabase RLS enforcing data access rules server-side (never
  trust client-side checks alone for who can see/edit what)
- **Testing:** JUnit + Turbine for ViewModel/StateFlow logic, plus dedicated
  unit tests for the attribute-calculation and simulation logic (if any of that
  ends up client-side) given how central correctness there is to the game
  feeling fair

---

## 12. Open Decisions (Need Your Input Before/During Build)

1. **Monetization model?** Free, one-time purchase, ads, or none for now?
2. **Guest mode vs. required login up front?** (Recommend: allow a guest draft,
   prompt to save via account afterward.)
3. **Fixture realism:** real historical fixture list vs. synthetic round-robin?
4. **Does the drafted XI replace a real team in the table, or play alongside as
   an extra entrant?**
5. **Simulation logic location:** server-side (recommended, §8.4) vs. on-device?
6. **Player photos / club crests:** include real images (licensing question) or
   stick to text/initials/generic position icons for v1?
7. **Save-history limits:** unlimited runs stored per user, or a cap (storage/
   cost management on the Supabase side)?
8. **Formation flexibility:** full custom drag-and-drop vs. fixed formation
   templates for v1?
9. **Trait system tuning:** rule-based thresholds (recommended) vs. weighted/
   statistical model?
10. **Backend platform confirmation:** proceeding with Supabase, or worth a
    quick comparison pass against Firebase/a custom backend first?

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
8. **Simulation engine v1** (recommend as a Supabase Edge Function per §8.4),
   using pilot data's league tables
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
- Simulation logic should default to running **server-side** (Edge Function)
  unless a later decision moves it on-device.
- **Visual/UX polish is a hard requirement, not a stretch goal** — build the
  Compose design system and shared component library (§10) deliberately and
  early, rather than styling screens ad hoc as they're built. Motion design
  (§10.4) is where most of the "premium feel" will actually come from — budget
  real effort there, especially for the spin/reveal and results-screen moments.
- MVP league scope: Premier League + La Liga, 2000–2025, architected to extend
  to Serie A, Bundesliga, Ligue 1 later.
- Tech stack: Kotlin, Jetpack Compose, MVVM, Supabase (Postgres+Auth), Room
  (cache), Hilt.
- Treat §12 "Open Decisions" as things to raise with the human before assuming
  an answer and building around it.
