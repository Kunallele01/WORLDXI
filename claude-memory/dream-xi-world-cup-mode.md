---
name: dream-xi-world-cup-mode
description: "World Cup Mode — agreed design, the data now loaded for 2006-2026, and the one remaining data gap"
metadata:
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-09-17T12:17:41.830Z
---

Researched 2026-09-12 from 7a0.com.br; data built and loaded, knockout engine built
2026-09-17 (see [[dream-xi-world-cup-engine-calibration]]), screens in progress.

## Agreed design (his decisions, do not re-litigate)
- Editions **2006-2026**. XI of **11, no bench**.
- Draft pool is **cross-era and cross-nation**: any player from any nation in any edition.
- The XI **replaces a nation that finished bottom of its group**, chosen by a
  `TakeoverDraw`-style draw (same shape as the club mode's relegation draw).
- **Real results stand** for every match not involving the user; his fixtures alter them,
  with a bracket cascade once he advances.

## Data now loaded and verified
- **Ratings:** the sofifa scrape added editions **07, 11 and 26** (all leagues, rating floor
  58) on top of the club-era 10/12/13/14 — `fifa_ratings_normalized.csv` is now 42,462
  sofifa rows. The merge is a **union, not a replacement**: 97 edition-11 players rated
  below 58 survive from the club scrape, which is how you prove it didn't clobber the
  2009/10-2013/14 anchors. `sofifa_collect.py` merges and `sofifa_editions.py` replaces
  only editions present in its input — both guards exist because the naive version wiped
  FIFA 10-14.
- **Fixtures:** `wc_fixtures.py` = 424 matches, all scored, 23 shootouts. 2006-2018 from
  the worldfootballR mirror, **2022 and 2026 from FBref** (the mirror has no 2022 scores
  and no 2026 at all). It asserts that rather than trusting it.
- **Groups:** `wc_groups.py` rebuilds group tables that no source labels — a WC group is a
  **clique** in the group-stage opponent graph, so connected components recover them
  exactly (asserted as fours). Real WC tiebreakers: points, GD, GF, then head-to-head.
  Spot-checked against reality (Germany bottom in 2018, Spain 3rd in 2014, Italy and
  France bottom in 2010) — all correct.
- **Takeover pool:** 52 slots, **39 distinct nations**.
- **`wc_nations.py`** maps FBref spellings to the ratings table. Two traps: FBref tags a
  country code onto names ("za South Africa"), and **EA renames countries between
  editions** — "Czechia" in FIFA 07/11/26 but "Czech Republic" in FIFA 15 — so one-way
  aliases cannot work and `EQUIVALENTS` tries every spelling.

## The one real gap
Thin squads are **era gaps in EA's league coverage**, not missing nations: Japan is 9
players in FIFA 07 and 472 in FIFA 19; Saudi Arabia is 0 until FIFA 15 adds 362. So an old
squad is only its Europe-based players.

This barely matters, because **opponent strength is only needed for nations the user
plays**, and nearly every broken nation is itself a bottom team we replace (2006 Costa
Rica/Iran/Japan/Saudi Arabia, 2010 Korea DPR, 2014 Iran). **The only genuine problem in
the whole mode is Egypt (9 players) and Iran (7) in 2026**, who are reachable opponents.
**Fixed and approved 2026-09-12** in `etl/wc_squads.py`: a nation under eleven players
borrows the nearest edition that reaches eleven, and `NationPool.source_edition` records
which — the same choice `FifaLookup._with_grid` makes for positional grids. Ties go to the
later edition; the club-era editions 10 and 12-14 are never borrowed from (PL + La Liga
only, so they describe two European clubs' worth of a nation, not its pool).

Result: 8 nation-editions borrow, and **Egypt and Iran 2026 both resolve to FIFA 24** at
distance 2 (13 and 16 players), on the same flat 17+ rating scale so the borrow does not
distort. Two nations stay unfieldable — Korea DPR 2010 (1 player) and Uzbekistan 2026 (6)
— and both finished bottom of their group, so they are replaced, never played. Nothing is
blocked.

Watch out: some borrows are far. Jordan 2026 and Saudi Arabia 2006 both fall back to
FIFA 15 (distance 11 and 8) because no nearer edition reaches eleven. Harmless for
simulation, since both are replaced nations — but the draft pool can browse any
nation-edition, so the UI should surface `borrowed`/`source_edition` rather than implying
those are really 2026 or 2006 squads. A borrow-distance cap was offered and not taken up.

Nations with no rated goalkeeper (Tunisia FC 26 has 30 players and no keeper) are **not** a
blocker: the draft pool spans nations, so his keeper never has to come from the nation he
replaces. Verified this is real and not a parsing bug — `positions` is ";"-joined with
"GK" standalone.

## Data and schema history
- **Supabase tables: `supabase/migration_0007_world_cup.sql` is WRITTEN, and the user runs
  it himself in the SQL Editor** — never me, per [[dream-xi-supabase-project]]. Nine tables
  (`wc_tournaments`, `wc_nations`, `wc_squads`, `wc_squad_players`, `wc_nation_entries`,
  `wc_matches`, plus `wc_runs`/`wc_picks`/`wc_results`). A squad is keyed by (nation,
  edition) and NOT owned by a tournament, because the draft pool is cross-era; the entry
  points at the squad actually used, which is how a borrow stays visible. Grants are
  included in the file: this project has "Automatically expose new tables" OFF, so a new
  table has no privileges for any role, service_role included.
- **Migration applied by the user 2026-09-17, and the data is LOADED** by `etl/load_wc.py`
  (dry run by default, `--load` to write; idempotent — reloading was verified to replace
  rather than duplicate; refuses to run once `wc_runs` has rows). Loaded: 6 tournaments,
  69 nations, 206 squads, 4,764 squad players, 208 entries, 424 matches. Verified through
  the ANON key: all six finals correct, 2018 takeover pool correct, anon writes get 401.
- **Squad size decided by the user: real sizes** — 23 for 2006-2018, 26 for 2022-2026.
  `wc_squads.national_squad`: best 3 keepers, then floors of 8 DEF / 7 MID / 5 ATT (by
  primary position), then best of the rest. Without floors a squad can come out with one
  full-back. Rejected: 26 everywhere, a 40-man pool, and loading all ~61k rated players.
- `wc_nations.canonical_name` gives one stored name per country (first spelling in each
  `EQUIVALENTS` tuple), so 2006 "Czech Republic" and 2026 "Czechia" are one nation.
- Group letters are REAL, from Wikipedia's group pages (superseded the date-ordered guess).
- Known data quirk: Egypt FIFA 24 is 13 players with 0 keepers and 1 defender, so an
  opponent-strength calculation must not assume a balanced squad.
- Knockout engine, real scorers (migration_0008) and host flags: DONE — see the calibration memory.
  Simulated goals use per-goal weighted draws, no season budget.

**How to apply:** run `python wc_fixtures.py`, `wc_groups.py`, `wc_squads.py` and
`verify_wc_squads.py` in `etl/` to re-check all of the above; they print their own
verdicts.

## Screen decisions (user, 2026-09-17)
- Tournament balance: "keep it faithful" — the dream XI winning ~19% stays. Do not tune.
- WHICH World Cup: RANDOM, revealed after the draft (same rule as club mode's season), then the
  takeover draw among that edition's bottom nations.
- Pacing: ROUND BY ROUND like the season screen — group matchdays 1-3, then each knockout round,
  one tap each, plus "Play the rest"; tables and bracket update as it goes.
- Entry: a "League | World Cup" MODE SWITCH at the top of the existing Setup screen; formation and
  change spins stay shared.
- Each draft round spins one nation-edition squad from all 208 (e.g. "Brazil · 2006"); XI of 11, no bench.

## Screens BUILT 2026-09-17 (217 tests green; installed on device, NOT yet exercised on it — phone was locked)
- Setup: League | World Cup mode switch (`RunMode`), WorldCupCard, "Start World Cup run"; Free Mode stays league-only.
- Draft: REUSES DraftScreen via `DraftUiState.mode = DraftMode.WorldCup` + `WcDraftViewModel`. Squad rows show EA
  positions and club (no season stats); reveal card shows `SpinResult.detail` (group finish + borrowed edition).
  World Cup players get a synthesized positional grid from WcModel costs so `ratingAt` == engine rating
  (WcScreensTest checks 196,916 player-slot pairs). Move/swap and pick invariants now live ONCE on
  `DraftUiState.withHeldMovedTo` / `withPick`, used by both draft ViewModels.
- Repeat-spin weights are the CLUB constants reused unmeasured for nations — flagged in code.
- Tournament: `WcTournamentViewModel` picks a random edition from the seed, TakeoverDraw among bottom nations,
  plays the whole tournament once, reveals `WcStages` (3 group matchdays, then each KO round; 3rd place rides with
  the final). Screen: user match card with real/sim scorers, ET/pens note, other results with ✦ for ties that never
  happened, user's group table, knockouts so far, champion + top scorers, Wikipedia CC BY-SA credit line.
- Data: `WorldCupRepository` pages reads (PostgREST 1,000-row cap; 2026 squads ~1,250 rows); `WorldCupRun` in-memory.

## Flags + Magic Mode decisions (2026-09-17)
- FLAGS: 70 PNGs (flagcdn.com public domain; Serbia & Montenegro from Wikimedia Commons) in drawable-nodpi as
  flag_<iso>.png, generated lookup `core/ui/NationFlags.kt` by `etl/wc_flags.py` (single mapping table, 73 display
  names). ClubBadge falls back crest -> nation flag (3:2 crop) -> initials. Test: every nation has a flag.
- MAGIC MODE (user chose): a WORLD CUP FREE MODE mirroring club Free Mode — browse nation -> World Cup -> squad,
  pick anyone anywhere, plus the Magic button (build best XI, tap tile to cycle ladder, keep hand-picked shirts and
  fill the rest). Magic pool = EVERY nation at EVERY World Cup (cross-era). Edition played = RANDOM after the XI,
  same as the draft.
- WORLD CUP FREE MODE BUILT 2026-09-17: `WcFreeModeViewModel` + `WcFreeModeRoute` REUSE `FreeModeScreen` via
  `FreeModeUiState.worldCup` (nations/World Cups wording, `SeasonOption.note` = "4th in Group H"). Magic pool =
  `WorldCupRepository.allSquadPlayers()` (~4.8k rows, paged, cached per run) through the unchanged `MagicXi`;
  reveal pacing constants now internal in FreeModeViewModel.kt and shared. No bench. Runs carry
  `CompletedWcDraft.isFreeMode` -> gold "· FREE MODE" in the tournament header. Setup WC side has a Free Mode button.
  Test magic XI (4-3-3): Neuer, Rodri, Terry, Ramos, Hakimi, Modric, Iniesta, De Bruyne, Neymar, Messi, Ronaldo;
  jitter keeps total within 1 point across presses. Rodri at full-back is the measured DM->FB cost (-2) at work.
- TAKEOVER DRAW SIZED FOR A WC FIELD (2026-09-18, user asked): 50 draws per nation, floor 500 -> 500 for eight
  nations, 600 for twelve. Club mode keeps its 300 for three clubs. Fairness was never the issue (exact at any
  count); SEPARATION was: measured over 2,000 runs, twelve nations at 300 draws went to sudden death 14.0% of the
  time with a winning margin of 2.9, against 10.3% and 4.0 at 600. Animation re-paced to a FRAME BUDGET
  (BULK_FRAMES=48 at 30ms, then a 12-draw tail easing 70->170ms), so 600 draws take ~3.4s — no slower than the old
  300 at ~4.0s, whatever the field size.
