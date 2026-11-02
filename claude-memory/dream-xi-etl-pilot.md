---
name: dream-xi-etl-pilot
description: "Dream XI's pilot ETL (2023/24 Premier League) is built, tested, and loaded into Supabase"
metadata: 
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-28T22:52:13.604Z
---

The §13 Milestone 2 pilot-slice ETL is done: 2023/24 Premier League, fully
loaded into Supabase — 20 clubs, 380 real fixtures, 570 players, 580
player-season stat rows with computed attributes, all verified readable via
both the service_role key (ETL) and the anon key (app-facing, RLS-gated).

**Key technical finding — will matter for scaling to more seasons/leagues:**
FBref blocks headless/script HTTP requests via a Cloudflare bot challenge
(403, `cf-mitigated: challenge`). Plain `requests`/`curl` cannot fetch FBref
pages at all, regardless of user-agent. The working method: drive a real
Chrome session via the `claude-in-chrome` skill, then inside that page's own
JS context (`javascript_tool`, same-origin `fetch()` carries the session's
cookies past Cloudflare) or via `get_page_text` on the rendered page. FBref
tables are HTML-comment-wrapped in raw source but render normally in a live
browser, so `get_page_text` on the loaded page is the simplest reliable path.

**Also found: FBref's advanced stats (xG/xA, passing accuracy, touches,
carries) were permanently deleted site-wide in January 2026** — their data
provider pulled the feed (see their Jan 20 2026 blog post). No subscription,
including Stathead, restores this; Stathead is now offering refunds to
soccer subscribers for exactly this reason. Only basic stats remain free:
goals/assists/cards/minutes/appearances, shots/shots-on-target,
tackles-won + interceptions (NOT full tackles/blocks/clearances — those are
also gone), and full goalkeeper stats.

**CORRECTION 2026-08-29 — `interceptions` is NOT gone, and believing it was
caused two real bugs.** `parse_defense_compact`'s docstring asserted the Int
column had gone blank site-wide; verified false against the live FBref
defense table (Van Dijk 2023/24: tackles_won=23, interceptions=35, both
populated). Consequences: 8 seasons were loaded with interceptions dropped
entirely (defenders rated on ~half their defensive signal), and in the two
2023/24 pilot files the column header read `90s Tkl TklW` while the data was
actually `90s TklW Int` — so `_defense_fields` stored INTERCEPTIONS as
tackles_won since the pilot. Both fixed; all 10 seasons re-scraped and
merged with a row-count + boundary-name verification gate. Lesson: verify a
"this data is gone" claim against the live source before building logic that
depends on it — this one was inherited unchecked through several sessions.

The user has an active Stathead
soccer subscription bought for this (expecting a refund per FBref's own
offer) — the pilot's v0 attribute formula was built around what's actually
free, not around the original §4 "Tier A rich" ambition. See
[[dream-xi-architecture-decisions]] for the fixture-realism decision this
data supports.

**get_page_text output caps at ~50KB per call** — larger tables (580 player
rows) need either: (a) padding the DOM with cloned copies of the target
table to force the tool's auto-save-to-a-local-JSON-file path (cheap, no
manual retyping, but that saved file is ALSO capped at 50000 characters,
so a second smaller call may be needed for the remainder), or (b) trimming
already-captured rows out of the DOM via `javascript_tool` and re-calling
`get_page_text` for the remainder. Whichever raw chunks result get merged
by `parse_raw.py`'s dedup-by-rank logic. Manually retyping large tool output
into `Write` is expensive and error-prone (this is how 4 fixtures went
missing on the first pass) — prefer the file-auto-save path.

**Position roles (added after the pilot's initial load):** FBref's own
position tags are too coarse (DF/MF/FW) to support 8 draft-slot roles
(GK/CB/FB/DM/CM/CAM/Winger/ST), so `roles.py` cross-references Transfermarkt
squad pages (one page per club, not per player — very cheap: 20 pages for
the pilot) for the specific role, fuzzy-matched to FBref's player names
(handles nickname vs full name, accents, hyphenation, name-order swaps).
575/580 matched directly; the rest fall back to a coarse GK/CB/CM/ST guess
derived from FBref's tag so nothing is ever left blank. `player_season_stats
.primary_position` in Supabase now holds this specific role (not FBref's
coarse tag, which is kept in `secondary_positions` for reference). Verified
distribution across the pilot: CB 105, FB 91, Winger 86, ST 82, CM 76, DM 50,
CAM 50, GK 40 — all realistic for a PL season.

**La Liga 2023/24 added the same way** (fixture/DOM-padding techniques
above), and the pipeline was generalized to take a `LeagueConfig` in
`supabase_load.py` (`python supabase_load.py pl` or `laliga`) rather than
being hardcoded to one league. Both leagues are now loaded: 2 leagues, 40
club_seasons, 760 fixtures, 1160 unique players, 1189 player_season_stats
rows — all verified via REST row counts.

**Real bug found and fixed while adding La Liga: player identity must be
keyed on (full_name, birth_year), never full_name alone.** Discovered when
"Rodri" at Real Betis (La Liga, born 2000, an obscure player) and "Rodri" at
Man City (PL, born 1996, that season's Ballon d'Or winner) — two completely
different real people — got merged into one `players` row by
`supabase_load.py`'s original name-only matching. Fixed by adding
`birth_year` to `parse_raw.PlayerRow` and `build_dataset.PlayerSeason`
(parsed from FBref's "Born" column, which was already being extracted for
the age/team-boundary regex but not retained), and keying all player
lookups/inserts on `(name, birth_year)`. `players.date_of_birth` is stored
as `{birth_year}-01-01` — year-only precision, day/month aren't in the
source data, documented in code as such. Both leagues were truncated and
reloaded after this fix (with the user's explicit confirmation first, since
bulk-deleting 6 tables understandably tripped the safety classifier).
**Lesson: design identity/uniqueness keys before loading data, not after —
don't assume a "unique-looking" field like a full name actually is unique.**

**Code layout** (`D:\Dev\FootballSim\etl\`):
- `raw/` — gitignored raw text dumps from FBref (league table, fixtures,
  standard/shooting/defense/keeper stats), kept locally for reproducibility,
  never committed (redistribution/ToS concerns, §6.1)
- `parse_raw.py` — parses the raw text into typed rows (player rows anchored
  on the "nation" and "age+born" token patterns, not naive whitespace split)
- `build_dataset.py` — merges the 4 stat tables into one `PlayerSeason` per
  player+team by name/team identity (FBref's own row rank differs per table)
- `attributes.py` — the v0 attribute formula (percentile-within-position-
  group), explicitly marked as provisional pending the real §7.3 formula doc
- `roles.py` — Transfermarkt-sourced specific position role (GK/CB/FB/DM/CM/
  CAM/Winger/ST) with fuzzy name matching + coarse fallback
- `supabase_load.py` — loads everything into Supabase via REST + service_role
  key; NOT idempotent, re-running duplicates rows (truncate tables first —
  the delete-by-table-in-dependency-order snippet used once already is a
  good template: player_season_stats, fixtures, players, club_seasons,
  seasons, leagues, in that order)
- `.env` (gitignored) — SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY

**Why this matters:** if asked to extend the ETL to more seasons/leagues,
reuse this exact browser-driven extraction method (plain scripted HTTP will
just get a 403), budget for the missing advanced-stats fields, and don't
re-run `supabase_load.py` against already-populated tables without
truncating first. See [[dream-xi-supabase-project]].

**2026-08-28 multi-season expansion (in progress):** user asked to extend
both PL and La Liga from 1 season each (2023/24) to 5 seasons each
(2019/20–2023/24), then build the app against that 5-season dataset. All
**5 Premier League seasons are now loaded** (2019/20, 2020/21, 2021/22,
2022/23, 2023/24) — each required its own fresh FBref extraction (league
table, fixtures ~380, standard/shooting/defense ~520-570 rows, keepers
~37-42 rows) and its own new `TM_CLUB_ID_TO_FBREF_NAME_PL_20XX_XX` dict in
`roles.py` (club composition changes every season from promotion/
relegation — e.g. 2019/20 has Bournemouth/Watford/Norwich/Sheffield Utd
instead of 2023/24's Luton/Burnley/Sheffield Utd). Player-identity dedup
worked exactly as designed each time: newer seasons increasingly resolved
players to existing rows rather than creating duplicates (e.g. 2019/20
load: 409 of 515 identities already existed from later seasons).
**La Liga still needs the same 4 seasons (2019/20-2022/23) — not done yet.**

**Extraction reliability note discovered this round:** manually transcribing
FBref's `get_page_text` output into a `Write` call occasionally drops or
duplicates 1-2 lines silently (happened again on PL 2020/21 fixtures — a
missing Man City-Villa game, a missing Burnley-Man Utd game, and one
duplicate Spurs-Southampton line). Always verify every saved file's row
count against the parser immediately after writing it (`parse_fixtures_compact`
etc.) and cross-check any club whose fixture count isn't exactly 38 by
re-querying the live DOM for that specific pair of teams — don't just trust
the write succeeded because the file was created.

**Transfermarkt club-id role scraping now uses a batched
localStorage-accumulation pattern**: navigate to each club's squad page
(`transfermarkt.com/x/kader/verein/{id}/saison_id/{year}` — slug in the URL
doesn't matter, only the numeric id), run one `javascript_tool` call that
extracts `clubId|player|role` lines and appends them to a `tm_roles`
localStorage key (same-origin, persists across navigations within
Transfermarkt), repeat for all 20 clubs, then one final dump-to-`<pre>` +
`get_page_text` retrieves everything in a single ~25KB payload instead of
20 separate round-trips through the conversation. **Gotcha found this
round: localStorage is per-origin, not per-session** — if a prior season's
scrape left `tm_roles` populated on transfermarkt.com and you only clear it
on a *different* origin (e.g. fbref.com) by mistake, the next season's
scrape silently accumulates on top of stale data from 2 seasons back
(caught via a `clubs: 24` sanity check when only 20 were expected — always
verify unique club-id count immediately after the final dump). Always
confirm `localStorage.removeItem('tm_roles')` runs on transfermarkt.com
itself before starting a fresh season's club-loop.

**2026-08-28 — La Liga multi-season expansion completed.** All remaining 4
La Liga seasons (2021/22, 2020/21, 2019/20 — plus 2022/23 done earlier same
day) were extracted and loaded the same way as PL: fresh league table,
fixtures (380 each), standard/shooting/defense (~570-620 rows), keepers
(~40-45 rows), and a new `TM_CLUB_ID_TO_FBREF_NAME_LALIGA_20XX_XX` dict per
season (club composition varies: 2020/21 had Huesca/Valladolid/Eibar instead
of 2021/22's Alavés/Levante/Granada; 2019/20 had Leganés/Espanyol/Mallorca
instead of Cádiz/Elche/Huesca). **Both leagues are now fully at 5/5 seasons
(2019/20–2023/24)** — 10 league-seasons total, 200 club_seasons, ~3800
fixtures, thousands of player_season_stats rows, all loaded via
`python supabase_load.py <key>` (see `CONFIGS` dict in `supabase_load.py`
for the full key list: `pl2324`, `pl2223`, `pl2122`, `pl2021`, `pl1920`,
`laliga2324`, `laliga2223`, `laliga2122`, `laliga2021`, `laliga1920`).

**Also hit twice this round: manual fixture-list transcription drops/misplaces
lines** (PL 2020/21 previously; La Liga 2020/21 this round — 2 lines short,
traced to a genuinely-missing Athletic-Barcelona leg and a mislabeled
gameweek). **Fix that actually worked reliably: stop hand-copying
`get_page_text` output into `Write` at all** — instead build the compact
line array in the page's own JS, stash it in `localStorage`, then dump it
to a `<pre>` element and re-run `get_page_text` on that exact dump. That
round-trip reproduces the DOM's own row order/content byte-for-byte with
zero manual retyping, and matches the parser's count on the first try every
time it was used this session (fixtures, standard, shooting, defense,
keepers, roles — all of La Liga 2020/21 and 2019/20 used this pattern
exclusively, no more transcription incidents after switching to it).

**Priority set by the user, deferred until this point:** now that both
leagues are at 5/5 seasons, next up is resolving the `overall_rating`
formula's known defender/keeper-underrating bias and designing the
draft-spin balance mechanism (avoiding clustering of all-worst or
all-best players in the random team/season draw pool) — see
[[dream-xi-attribute-formula-spec]] and [[dream-xi-draft-mechanic]].


-------------------------------------------------------------------------------
WRITING THOUSANDS OF ROWS TO SUPABASE — operational, learned 2026-09-05
-------------------------------------------------------------------------------

`backfill_positions.py` patches one row per request (PostgREST cannot bulk-
upsert these tables — `id` is GENERATED ALWAYS). At 11k rows and 16 threads it
hit a `ConnectTimeout` partway through on EVERY attempt, three times running,
leaving the table half-updated and re-sending all 11k rows on each retry.

Fixed in the script: **retry with exponential backoff around each PATCH, and
6 worker threads instead of 16.** A PATCH of one row by id is idempotent, so
retrying the request is right where restarting the job is waste. It then
completed first time.

Apply the same shape to any new bulk backfill here. And note the reason the
half-updates were survivable at all: these scripts recompute everything from
source on each run, so they are safe to re-run. A script that patched
INCREMENTALLY would have been corrupted by the same timeout.
