-- ============================================================================
-- Dream XI — migration for "change spins" (rerolls) in the draft.
--
-- Run this in the Supabase SQL Editor. Safe to run any time — draft_runs has
-- zero rows so far (no real gameplay yet), so there's nothing to backfill.
--
-- Why: each draft round spins a random (season, club) and the player picks
-- from that squad. A player who dislikes the squad that came up can spend a
-- "change spin" to reject it and spin again. The allowance is a difficulty
-- setting (1-3, default 2), so it must be stored PER RUN rather than read
-- from app settings at simulate time — otherwise changing the setting later
-- would retroactively rewrite the difficulty of a finished run.
--
-- Measured effect (etl/draft_sim.py, 4000 simulated drafts each):
--     0 rerolls -> XI mean 81.5
--     1 reroll  -> 81.9
--     2 rerolls -> 82.3
--     3 rerolls -> 82.5
-- i.e. roughly +0.4 rating per reroll — a gentle dial, and no setting is
-- strong enough to trivialise a run. Note it does NOT raise the weakest pick
-- in an XI (stuck near 77 at every setting): late in a draft only awkward
-- slots remain, and a reroll just redraws from the same distribution.
-- ============================================================================

alter table public.draft_runs
    add column if not exists rerolls_allowed smallint not null default 2,
    add column if not exists rerolls_used smallint not null default 0;

-- 1-3 is the range the settings screen exposes; keep the DB honest about it
-- rather than trusting the client.
alter table public.draft_runs
    drop constraint if exists draft_runs_rerolls_allowed_range;
alter table public.draft_runs
    add constraint draft_runs_rerolls_allowed_range
    check (rerolls_allowed between 1 and 3);

alter table public.draft_runs
    drop constraint if exists draft_runs_rerolls_used_valid;
alter table public.draft_runs
    add constraint draft_runs_rerolls_used_valid
    check (rerolls_used >= 0 and rerolls_used <= rerolls_allowed);

-- A rejected squad is part of the run's story ("I binned Sheffield United
-- 23/24"), and without recording it there is no way to show that history or
-- to verify rerolls_used against what actually happened.
create table if not exists public.draft_rerolls (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.draft_runs (id) on delete cascade,
    round_number integer not null,
    rejected_club_season_id bigint not null references public.club_seasons (id),
    created_at timestamptz not null default now()
);

create index if not exists draft_rerolls_run_id_idx on public.draft_rerolls (run_id);

alter table public.draft_rerolls enable row level security;

-- Mirrors the draft_picks policy: a row is reachable only through a run the
-- caller owns.
drop policy if exists "Users manage their own draft rerolls" on public.draft_rerolls;
create policy "Users manage their own draft rerolls" on public.draft_rerolls
    for all
    using (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    );
