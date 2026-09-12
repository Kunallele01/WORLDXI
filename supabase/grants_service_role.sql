-- ============================================================================
-- Dream XI — service_role grants for the ETL (§6.3).
--
-- Run this in the Supabase SQL Editor. Needed because disabling "Automatically
-- expose new tables" during project setup turned out to withhold grants from
-- service_role too, not just anon/authenticated — the ETL's INSERTs were
-- getting a bare "permission denied" before RLS (which service_role bypasses
-- anyway) even entered the picture.
-- ============================================================================

grant all on
    public.leagues,
    public.seasons,
    public.club_seasons,
    public.fixtures,
    public.players,
    public.player_season_stats,
    public.profiles,
    public.draft_runs,
    public.draft_picks,
    public.draft_rerolls,
    public.simulation_results
to service_role;

grant usage, select on all sequences in schema public to service_role;
