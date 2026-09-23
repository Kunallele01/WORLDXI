-- ============================================================================
-- ERA XI — per-player positional grids for World Cup squad players.
--
-- Run this in the Supabase SQL Editor, after migration_0008. Safe to re-run.
--
-- WHY. World Cup players were priced out of position from ONE table of median
-- costs, which put a 90-rated Rodri at left-back for a 2-point cost when EA's
-- own grid for him says 7 (87 at DM, 80 at full-back). Now each player carries
-- EA's grid as the change from his primary role to every role:
--   * his own edition's grid where it has one (48% of outfield squad players),
--   * otherwise the nearest edition of the SAME PERSON within two editions (20%),
--   * otherwise nothing (31%), and the app prices him from a rating-banded table.
-- Borrowing is capped at two editions because a grid is one point in a career:
-- measured against every real World Cup match, unrestricted borrowing predicted
-- results worse (log-loss 0.9559) than the capped version (0.9500).
--
-- Stored as DELTAS, not ratings: a borrowed grid comes from a different
-- edition's rating scale, and only the change between roles carries across.
-- ============================================================================

alter table public.wc_squad_players
    add column if not exists grid_cb smallint,
    add column if not exists grid_fb smallint,
    add column if not exists grid_dm smallint,
    add column if not exists grid_cm smallint,
    add column if not exists grid_cam smallint,
    add column if not exists grid_winger smallint,
    add column if not exists grid_st smallint,
    -- The FIFA edition the grid came from: this player's own, or the one borrowed.
    add column if not exists grid_edition text;
