-- ============================================================================
-- Dream XI — table-level grants, needed on top of schema.sql's RLS policies.
--
-- Run this once, after schema.sql, in the Supabase SQL Editor.
--
-- Why this is a separate step: RLS policies only apply once a role already
-- has the underlying table-level privilege (GRANT). We disabled "Automatically
-- expose new tables" when creating the project (deliberately, for explicit
-- per-table control) — that setting is exactly what would have auto-run
-- these grants, so we run them by hand instead.
-- ============================================================================

-- Reference data: readable by both anonymous and signed-in clients (guest
-- drafts, §5.4, need to read the player pool before any login happens).
grant select on
    public.leagues,
    public.seasons,
    public.club_seasons,
    public.fixtures,
    public.players,
    public.player_season_stats
to anon, authenticated;

-- User data: only signed-in clients may read/write at all; RLS policies
-- (schema.sql) then further restrict each row to its own user.
grant select, insert, update, delete on
    public.profiles,
    public.draft_runs,
    public.draft_picks,
    public.draft_rerolls,
    public.simulation_results
to authenticated;
