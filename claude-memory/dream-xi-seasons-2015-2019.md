---
name: dream-xi-seasons-2015-2019
description: "2014/15-2018/19 PL + La Liga are LOADED (10 seasons per league now): where the data came from with no scraping, the matcher traps found, and why doubling the pool barely moved the draft"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-09-04T21:31:01.941Z
---

**LOADED 2026-09-05.** The game now holds **10 seasons per league**: 20
seasons, 400 club-seasons, 11,157 player-seasons, 7,600 fixtures. Staged first
to `etl/staged/` (30 TSVs), verified, then loaded with
`load_staged_2015_2019.py --apply` followed by
`backfill_squad_strength.py --apply`.

Verified in the DATABASE afterwards, not just in the files: all ten champions
and points correct, 100% of rows rated, 94-99% carrying a positional grid,
87-97% carrying xG, 380 fixtures per season, **zero duplicate (name, dob)
identities** across 3,698 players, and all 400 club-seasons visible in
`club_season_draft_pool`.

**The load failed once, mid-flight, and the recovery is the lesson.**
`stats_source` is constrained by migration_0006 to exactly
`fbref_archive` / `understat` / `fbref_live`; I invented
`understat+fbref_archive` and PostgREST rejected the first batch (code 23514).
It had already written the `seasons` row and its 20 `club_seasons`, so a
retry would have been SKIPPED by the idempotency guard and left a half-season
behind. Fix was to delete the stub season (FKs cascade from `seasons`, so one
delete took the clubs with it) and re-run. The orphaned `players` rows from
the aborted attempt were left alone deliberately and were correctly reused on
the retry — PL 2014/15 reported "0 new identities". **Check the CHECK
constraints in the migrations before inventing an enum value.**

**Doubling the pool barely moved the draft.** `strength_quartile` is
`ntile(4)` over ALL club-seasons, so loading 200 more re-tiers the old ones:
only 6 of 200 modern club-seasons changed quartile, each by one step. And the
new seasons spread evenly across quartiles (52/47/54/47) with mean strength
78.90 against the modern 79.00 — **older squads are NOT systematically weaker**
under a FIFA-anchored rating, so no era handicap is needed.

**Scope decision: 2013/14 IS EXCLUDED, and do not quietly add it.** Understat's
history starts at 2014/15 — `season=2013` returns an empty list, not an error —
so no xG, xA or key passes exist for 2013/14 anywhere. The attack model is
npxG-based, so that season could not feed the same pipeline. The user chose to
stop at 2014/15 rather than carry one crippled season.

**NO SCRAPING WAS NEEDED, and this is the reusable finding.** Everything was
already machine-reachable, which is why it ran unattended:
- `worldfootballR_data/data/match_results/{ENG,ESP}_match_results.rds` — tier-1
  results back to **1993**. Fixtures for any season we might ever want.
- League tables **derived** from those 380 results, never fetched. A table is a
  function of the results; a separate source can only disagree with them.
- `etl/wfr/*.rds` already covered **2009/10+** for standard/shooting/misc/
  keepers. Only defense/passing/possession start at 2017/18.
- `etl/archive/male_players.csv` already held **FIFA 15-24**, grids included.
- Understat needs no browser (gzipped JSON, plain urllib).

So the practical floor for future expansion is **2014/15**, set by Understat,
not by FBref. Everything older would be a season without xG.

**Config changes made:** `KEPT_EDITIONS` in `fifa_ratings.py` now includes
15-19, and `SEASON_EDITIONS` in `fifa_lookup.py` gained the five seasons under
the same N+1 rule. `fifa_ratings_normalized.csv` was rebuilt — the pre-existing
editions' rows were diffed and are **byte-identical**, so the loaded seasons
cannot have shifted.

**THREE MATCHER TRAPS, all found by verification rather than by reading code.**
Each would have been invisible in the output:

1. **`clubs_agree` cannot match "Athletic Club" to itself.** Both "athletic"
   and "club" are stopwords, so both sides reduce to an empty token set. Cost:
   every one of Bilbao's 129 player-seasons lost its xG. Fixed with an
   exact-normalised-name escape hatch in `same_club`. **Any club whose whole
   name is stopwords hits this** — worth remembering before adding a league.
2. **Understat merges mid-season transfers into ONE row** ("Arsenal,Chelsea"),
   and `clubs_agree` happily matches that against "Arsenal". That stapled
   Giroud's full 953-minute season onto his 388 Arsenal minutes. Comma rows are
   now skipped on both match paths — same policy as the loaded seasons.
3. **A unique surname match is NOT proof of identity.** Swansea 2017/18 holds
   Jordan Ayew in Understat and Andre Ayew in FBref (Andre left in January), so
   the surname rule matched the brothers to each other and would have given
   Andre 7 goals' worth of his brother's xG. `find_in_club` now requires the
   candidate's goal count to be within 2 of ours.

That last one generalises: **both sources count the same league goals, so goal
agreement is a free identity check on any name match.** It took the failure
rate from 1.28% to 0.00% across 5,101 matched rows.

**Why La Liga needed a looser matcher at all:** Spanish football is full of
mononyms. Understat says "Thievy", FBref says "Thievy Bifouma"; "Michel" vs
"Michel Macedo". Exact-name matching gave La Liga 80% against the PL's ~93%.
Matching within a CONFIRMED club (pool of ~27) plus the goal check took it to
96%. Never loosen names league-wide.

**`primary_position` for these seasons comes from the matched EA row**, not
Transfermarkt. Deliberate: grid and position then share one source and cannot
disagree, so these seasons are immune by construction to fault C in
[[dream-xi-fifa-grid-faults]].

**Verified before staging finished** (`verify_staged_2015_2019.py`, all pass):
champion/points/last place against recorded history for all ten; golden boot
by name and tally for all ten (Ronaldo 48, Suarez 40, Messi 37/34/36, Salah 32,
Kane 25/29, Aguero 26, Aubameyang 22); best-XI rating vs final position
r = -0.82 to -0.96, matching the loaded seasons' -0.87.

See [[dream-xi-missing-stats-sources]] for the column-by-column source map and
[[dream-xi-rating-anchor]] for why the rating is FIFA-anchored.
