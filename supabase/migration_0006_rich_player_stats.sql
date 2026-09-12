-- ============================================================================
-- Dream XI — the per-player stats that make every position simulable.
--
-- Run in the Supabase SQL Editor. Safe to re-run; every column is nullable and
-- backfilled by etl/backfill_rich_stats.py.
--
-- WHY
-- ---
-- Ratings are FIFA-anchored, so nothing here changes overall_rating. What it
-- changes is whether a match engine has anything to reason about beyond
-- comparing two overalls, and whether a centre-back or a goalkeeper can be
-- described by what he actually did. Before this, a defender's only real
-- signals were tackles and his team's goals-against; xG, xA, key passes,
-- clearances, blocks, aerials, dribbles and post-shot xG were all either
-- absent columns or 0% populated.
--
-- WHERE THE DATA COMES FROM, AND THE ASYMMETRY THAT MATTERS
-- --------------------------------------------------------
-- FBref stripped its Opta-derived columns RETROACTIVELY when it changed
-- provider — old seasons included. Measured live on PL 2021/22 (546 rows):
-- the whole passing and possession tables read 0/546, clearances and blocks
-- read 0/546, and xG/xAG/progressive no longer exist as columns. Interceptions,
-- tackles won, shots, cards, fouls and crosses do survive.
--
-- The pre-strip tables were mirrored by the worldfootballR project, so three
-- of our five seasons are fully recoverable. The other two are not:
--
--   2019/20, 2020/21, 2021/22 -> full rich set   (source 'fbref_archive')
--   2022/23, 2023/24          -> xG/xA/key passes only, from Understat
--                                (source 'understat')
--
-- A fourth archived season exists (2022/23) and was DELIBERATELY REJECTED: it
-- is a mid-season snapshot, max 1080 minutes against 3420 elsewhere, median
-- 450, no player over 3000. Van Dijk reads 990 there against 3060 the year
-- before. The rows are well-formed, so loading them would have silently
-- entered twelve games of every total as a full season.
--
-- `stats_source` records which tier each row got, so the asymmetry stays
-- visible. THE SIMULATION SHOULD USE ONLY COLUMNS EVERY SEASON HAS —
-- otherwise a 2023/24 player loses to a 2021/22 player because of a data gap
-- rather than because he was worse, which is the one failure mode that would
-- discredit a result. The richer columns are for descriptions and analysis,
-- where saying less about some seasons is merely a bit thinner.
-- ============================================================================

-- --------------------------------------------------- universal to all seasons
-- xG family. Understat covers all five seasons at 100%, so these are safe for
-- the engine to depend on.
alter table public.player_season_stats
    add column if not exists npxg numeric,
    add column if not exists xag numeric;

comment on column public.player_season_stats.npxg is
    'Non-penalty expected goals. From Understat for every season; separating '
    'penalties matters because a penalty taker''s xG flatters his open-play threat.';

-- ------------------------------------------------- three seasons only (rich)
-- Defensive volume, for centre-backs and holding midfielders. Note these are
-- WORKRATE signals, never quality ones: verified across all ten loaded
-- seasons, centre-backs at the best defences average FEWER tackles and
-- interceptions per 90 than those at the worst.
alter table public.player_season_stats
    add column if not exists blocks integer,
    add column if not exists errors integer,
    add column if not exists recoveries integer,
    add column if not exists touches integer,
    add column if not exists shot_creating_actions integer,
    add column if not exists goal_creating_actions integer;

-- Goalkeeper-specific. Post-shot xG minus goals allowed is the closest thing
-- to a pure shot-stopping measure available: it prices the difficulty of what
-- he faced, which raw save percentage does not — a keeper behind a dominant
-- defence faces few but excellent chances and looks worse than he is.
alter table public.player_season_stats
    add column if not exists psxg numeric,
    add column if not exists psxg_prevented numeric,
    add column if not exists crosses_stopped_pct numeric,
    add column if not exists sweeper_actions integer;

comment on column public.player_season_stats.psxg_prevented is
    'Post-shot xG minus goals conceded. Positive = saved more than an average '
    'keeper would from the same shots. Only populated for 2019/20-2021/22.';

-- ------------------------------------------------------------- provenance
alter table public.player_season_stats
    add column if not exists stats_source text;

alter table public.player_season_stats
    drop constraint if exists player_season_stats_stats_source_valid;
alter table public.player_season_stats
    add constraint player_season_stats_stats_source_valid
    check (stats_source is null or stats_source in ('fbref_archive', 'understat', 'fbref_live'));

comment on column public.player_season_stats.stats_source is
    'Which tier of stats this row received. fbref_archive = full rich set '
    '(2019/20-2021/22); understat = xG/xA/key passes only (2022/23, 2023/24). '
    'Anything the simulation depends on must exist in BOTH tiers.';
