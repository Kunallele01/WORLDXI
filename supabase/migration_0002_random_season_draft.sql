-- ============================================================================
-- Dream XI — migration for the "random season per spin" draft redesign.
--
-- Run this in the Supabase SQL Editor. Safe to run any time — draft_runs and
-- simulation_results have zero rows so far (no real gameplay has happened
-- yet; the Draft/Simulate screens are still placeholders), so there's no
-- data to migrate, just structure to fix.
--
-- Why: each draft round now spins a random (season, club) pair independently
-- — there's no single "season" tied to a draft run anymore, so
-- draft_runs.season_scope no longer means anything. Which season gets
-- simulated, and which of its real bottom-3 clubs the XI replaces, are both
-- decided AFTER the draft completes, at simulate time — so
-- replaced_club_season_id belongs on simulation_results, not draft_runs.
-- ============================================================================

alter table public.draft_runs drop column if exists season_scope;
alter table public.draft_runs drop column if exists replaced_club_season_id;

alter table public.simulation_results
    add column if not exists replaced_club_season_id bigint references public.club_seasons (id);
