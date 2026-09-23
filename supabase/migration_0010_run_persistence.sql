-- ============================================================================
-- migration_0010 — what a saved run needs that the original tables lack
--
-- The user-owned tables have existed since schema.sql and have never held a
-- row, because the app has never had a session. Wiring one up exposed four
-- things they cannot currently record. All four are additive: nothing already
-- stored changes meaning, and the app works against the old shape until it is
-- taught otherwise.
--
-- Run this in the Supabase SQL editor.
-- ============================================================================

-- 1. THE SEED. The single most important column here, and the one whose
--    absence would make a saved run a lie.
--
--    A run's season, its takeover draw and every result it produced come from
--    one seed fixed when the draft completes. Save a run without it and
--    reopening it re-rolls the league underneath the player: the same XI, the
--    same opponents, a different table. Nullable because runs saved before
--    this column existed have no seed to give, and a missing seed has to read
--    as "cannot be replayed" rather than as zero, which is a valid seed.
alter table public.draft_runs add column if not exists seed bigint;
alter table public.wc_runs add column if not exists seed bigint;

-- 2. WHICH MODE BUILT THE XI. A 90-rated Free Mode side winning a league is a
--    different achievement from a drafted one doing it, and the season screens
--    already say so on screen. A history that forgets the difference would put
--    them side by side as equals.
alter table public.draft_runs
    add column if not exists is_free_mode boolean not null default false;

-- 3. FREE MODE HAS NO ONE LEAGUE. league_id was not null because a draft spins
--    within a league it chose at setup. A Free Mode XI is assembled from any
--    club of any season, so it can span both leagues at once and genuinely has
--    no single answer — which is not the same as it being unknown.
alter table public.draft_runs alter column league_id drop not null;

-- 4. THE BENCH. Eight substitutes are auto-filled from the squads a run passed
--    through. They never change how good the side is, but they do change WHO
--    SCORES, so a run reloaded without them reports different scorers than the
--    one that was played.
--
--    Carried in draft_picks rather than a table of its own: a bench player is
--    a pick that is not in the XI, and splitting them would mean two queries
--    and two shapes to keep in step for no gain. A bench pick has no position
--    on the pitch, so slot_position becomes nullable for them.
alter table public.draft_picks
    add column if not exists is_bench boolean not null default false;
alter table public.draft_picks alter column slot_position drop not null;

-- A slot is required for a starter and meaningless for a substitute. Stated
-- here so the database enforces it rather than trusting the app to.
alter table public.draft_picks drop constraint if exists draft_picks_slot_for_starters;
alter table public.draft_picks add constraint draft_picks_slot_for_starters
    check (is_bench or slot_position is not null);

-- Reading a player's history means "my runs, newest first", every time.
create index if not exists draft_runs_user_idx
    on public.draft_runs (user_id, created_at desc);
create index if not exists wc_runs_user_idx
    on public.wc_runs (user_id, created_at desc);

-- ============================================================================
-- Grants
--
-- This project has "automatically expose new tables" switched OFF, so every
-- migration states its own grants. These tables were granted in schema.sql and
-- migration_0007; the statements are repeated because a migration that assumes
-- an earlier one granted something is a migration that breaks when run against
-- a fresh project. RLS still decides WHICH rows — these only open the door.
-- ============================================================================

grant select, insert, update, delete on public.profiles to authenticated;
grant select, insert, update, delete on public.draft_runs to authenticated;
grant select, insert, update, delete on public.draft_picks to authenticated;
grant select, insert, update, delete on public.simulation_results to authenticated;
grant select, insert, update, delete on public.wc_runs to authenticated;
grant select, insert, update, delete on public.wc_picks to authenticated;
grant select, insert, update, delete on public.wc_results to authenticated;

grant usage, select on sequence public.draft_runs_id_seq to authenticated;
grant usage, select on sequence public.draft_picks_id_seq to authenticated;
grant usage, select on sequence public.simulation_results_id_seq to authenticated;
grant usage, select on sequence public.wc_runs_id_seq to authenticated;
grant usage, select on sequence public.wc_picks_id_seq to authenticated;
grant usage, select on sequence public.wc_results_id_seq to authenticated;

-- NOTE ON ANONYMOUS PLAYERS. Supabase gives an anonymous sign-in the
-- `authenticated` role, not `anon` — `anon` is the key used before anyone
-- signs in at all. So the grants above cover a guest, and the owner-only RLS
-- policies already on these tables keep one guest out of another's runs
-- without any change here.
