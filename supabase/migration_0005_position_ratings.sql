-- ============================================================================
-- Dream XI — per-player positional ratings, for free slot assignment.
--
-- Run this in the Supabase SQL Editor. Safe to run any time and safe to
-- re-run; every column added here is nullable and backfilled by the ETL.
--
-- WHY
-- ---
-- The draft used to lock each slot to a role: only a centre-back could fill a
-- CB slot. That is being removed — any outfield player may fill any outfield
-- slot — and the cost of playing someone out of position is expressed as a
-- rating change instead of a hard rule.
--
-- Two reasons the old lock had to go:
--   - 22% of loaded squads contain NO central attacking midfielder at all and
--     7% contain no defensive midfielder, so those slots were routinely
--     unfillable from the squad in front of you.
--   - Measured over 4,000 simulated drafts per setting, a locked draft failed
--     to complete an XI in 2.1% (Premier League) to 6.2% (La Liga) of runs
--     with no rerolls. Low, but the failure is total when it happens.
--
-- WHERE THE NUMBERS COME FROM
-- ---------------------------
-- Not invented. EA's own dataset carries all 27 positional-rating columns
-- (ls, st, rs, lw, ..., lb, lcb, cb, rcb, rb, gk) for every player in every
-- edition, i.e. EA has already decided what each individual player rates at
-- each position. We read those rather than modelling a penalty ourselves.
--
-- Stored PER PLAYER, not as one shared role matrix, because the per-player
-- values carry real information a matrix throws away: David Alaba is a
-- centre-back who genuinely rates well at left-back, and a role matrix would
-- charge him the same penalty as a centre-back who cannot play there at all.
--
-- For scale, the population medians (n=19,180 player-seasons at OVR>=70) —
-- these are what the per-player values vary AROUND, not what is stored:
--
--   natural \ slot   CB     FB     DM     CM    CAM  Winger    ST
--   CB              +0     -6     -4    -13    -18    -20    -19
--   FB              -4     -2     -4     -6     -7     -6    -10
--   DM              -2     -4     -1     -3     -6     -8     -9
--   CM              -6     -5     -2     +0     -1     -3     -5
--   CAM            -22    -16    -14     -3     +0     -1     -4
--   Winger         -24    -17    -17     -6     -1     +0     -4
--   ST             -25    -23    -22    -10     -4     -3     +0
--
-- Note it is ASYMMETRIC: a central midfielder loses 2 points dropping to
-- defensive midfield, a striker loses 22 doing the same. A hand-built matrix
-- would almost certainly have made that symmetric and been wrong.
-- ============================================================================

-- ------------------------------------------------------- positional ratings
-- One column per DRAFT SLOT rather than per EA position, because the slots
-- are what the game actually assigns to. EA's left/right variants collapse to
-- a single value on purpose: its `lb` and `rb` columns are IDENTICAL for every
-- player in the dataset, so EA models side as identity rather than ability and
-- storing both would imply a distinction the source does not make.
alter table public.player_season_stats
    add column if not exists pos_rating_cb smallint,
    add column if not exists pos_rating_fb smallint,
    add column if not exists pos_rating_dm smallint,
    add column if not exists pos_rating_cm smallint,
    add column if not exists pos_rating_cam smallint,
    add column if not exists pos_rating_winger smallint,
    add column if not exists pos_rating_st smallint;

comment on column public.player_season_stats.pos_rating_cb is
    'EA positional rating for this player at centre-back, for the season''s anchor '
    'edition. Null when no EA position grid could be resolved; the app then falls '
    'back to the role-level median matrix. Backfilled by etl/backfill_positions.py.';

-- Which side of the pitch, where EA states it: 'L', 'R', 'B' (both) or null.
-- Resolvable for ~93% of fullback/winger seasons (left 38% / right 36% /
-- both 21% / unknown 2%).
--
-- Used to price a wrong-flank placement. Note this is the ONE part of the
-- rating model that is a design choice rather than a measurement: EA records
-- which flank a player belongs to in his position list, but its left/right
-- rating columns are identical for every player in the file, so the source
-- offers no magnitude to derive. The first version read that silence as
-- "side is free", which let a left winger take the right wing at his full
-- rating. Magnitudes live in the app (SquadPlayer.sidePenaltyAt) precisely
-- because they are tunable opinion, not data.
alter table public.player_season_stats
    add column if not exists position_side text;

alter table public.player_season_stats
    drop constraint if exists player_season_stats_position_side_valid;
alter table public.player_season_stats
    add constraint player_season_stats_position_side_valid
    check (position_side is null or position_side in ('L', 'R', 'B'));

-- Which EA edition the grid above was read from. Recorded because 2023/24 is
-- a known exception: its anchor edition is EA FC 25, whose export carries no
-- positional grid at all, so those rows borrow FC 24's. Positional aptitude
-- moves far more slowly than rating does, which makes that borrow safe — but
-- it must be visible rather than silent, so it is written down.
alter table public.player_season_stats
    add column if not exists pos_rating_edition text;

-- --------------------------------------------------------------- provenance
-- players.nationality already exists in schema.sql but was never populated
-- (0 of 2,364 rows). It is career-level rather than per-season, so it stays on
-- players. Backfilled from the same EA join that supplies the ratings.
comment on column public.players.nationality is
    'Country name from the EA dataset, resolved through the same name+birth-year '
    'match used for ratings. Backfilled by etl/backfill_positions.py.';
