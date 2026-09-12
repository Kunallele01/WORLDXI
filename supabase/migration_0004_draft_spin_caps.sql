-- ============================================================================
-- Dream XI — migration for draft spin CAPS.
--
-- Run this in the Supabase SQL Editor. Safe to run any time and safe to
-- re-run; draft_runs has no gameplay rows yet, and the club_seasons column
-- added here is nullable until the ETL backfills it.
--
-- WHAT THIS IS (and what it deliberately is NOT)
-- ----------------------------------------------
-- Each draft round spins a random (season, club) and the player picks from
-- that squad. Two different ideas were considered for stopping a run turning
-- into an all-relegation or all-superclub slog:
--
--   (a) REJECTED — make strong/weak squads less likely on EVERY spin
--       (a Gaussian taper on the draw weights). Measured over 6,000
--       simulated drafts: the lopsided run it guards against happens 0.27%
--       of the time, while the taper crushed the spread of outcomes from
--       3.5 to 2.1 rating points. It made every draft feel identical in
--       order to prevent a 1-in-370 event. Do not reintroduce this.
--
--   (b) THIS — cap how many spins in a SINGLE RUN may come from the weakest
--       and strongest tiers, and how often the same squad may recur. It does
--       nothing at all in a normal draft and only intervenes when a run is
--       genuinely lopsided (~1 in 6 drafts at a cap of 4), costing almost no
--       variety (spread 3.5 -> 3.1).
--
-- Measured frequencies (etl/draft_sim.py, 6,000 runs, 11 spins each):
--     runs with >4 spins from the weakest quartile : 15.5%
--     runs with >4 spins from the strongest quartile: 11.7%
--     runs that spin the SAME club-season twice     : 23.4%
-- Caps do NOT raise the XI's mean rating (81.6 either way) or its weakest
-- player (77 either way). They shape how the draft FEELS, not how strong the
-- resulting team is — that is the whole point, and the tests above are the
-- evidence, not an assumption.
-- ============================================================================

-- ---------------------------------------------------------------- strength
-- Tier caps need the DB to know which squads are weak/strong. That is
-- currently computed only in Python (etl/squad_strength.py), so persist it.
-- Definition (kept identical to the ETL): the mean overall_rating of the
-- club's CORE_SQUAD=14 highest-rated players with >= 450 minutes. Whole-squad
-- means were rejected — a 40-man list is dominated by fringe players who were
-- never really part of that team, which flattens every club toward the middle.
-- Validated against reality: correlation with actual final league position is
-- -0.814 across all 200 club_seasons.
alter table public.club_seasons
    add column if not exists squad_strength numeric(5, 2);

comment on column public.club_seasons.squad_strength is
    'Mean overall_rating of the 14 highest-rated players with >=450 minutes. '
    'Backfilled by etl/backfill_squad_strength.py; recompute after any ratings change.';

-- Tiers are derived, never stored: the quartile boundaries move as more
-- seasons are loaded, and a stored tier would silently go stale. A view keeps
-- them correct by construction.
create or replace view public.club_season_draft_pool as
select
    cs.id                as club_season_id,
    cs.season_id,
    cs.club_name,
    cs.final_position,
    cs.squad_strength,
    ntile(4) over (order by cs.squad_strength) as strength_quartile
from public.club_seasons cs
where cs.squad_strength is not null;

comment on view public.club_season_draft_pool is
    'Draft-eligible club_seasons with a strength quartile. quartile 1 = weakest, '
    '4 = strongest; the draft caps count spins landing in 1 and 4.';

-- ------------------------------------------------------------- per-run caps
-- Stored PER RUN for the same reason as rerolls_allowed (migration_0003):
-- these are difficulty settings, and reading them from app config at
-- simulate time would let a later settings change retroactively rewrite the
-- difficulty of a run that is already finished.
alter table public.draft_runs
    add column if not exists cap_weak_spins smallint not null default 4,
    add column if not exists cap_elite_spins smallint not null default 4,
    add column if not exists max_same_club_season smallint not null default 2;

-- A cap below 2 would bind on most runs and start dictating the draft rather
-- than guarding it; above 11 it can never fire. 0 is disallowed outright —
-- it would make a whole quartile of real teams undraftable.
alter table public.draft_runs
    drop constraint if exists draft_runs_cap_weak_range;
alter table public.draft_runs
    add constraint draft_runs_cap_weak_range check (cap_weak_spins between 2 and 11);

alter table public.draft_runs
    drop constraint if exists draft_runs_cap_elite_range;
alter table public.draft_runs
    add constraint draft_runs_cap_elite_range check (cap_elite_spins between 2 and 11);

alter table public.draft_runs
    drop constraint if exists draft_runs_max_same_club_range;
alter table public.draft_runs
    add constraint draft_runs_max_same_club_range check (max_same_club_season between 1 and 11);

-- ------------------------------------------------------- eligibility helper
-- Enforcement lives here rather than only in the client so the rules cannot
-- drift between platforms, and so a spin cannot be re-rolled client-side
-- until a nicer squad appears. Returns the club_seasons a given run may still
-- legally spin, honouring all three caps.
--
-- SECURITY INVOKER (the default) is deliberate: the function reads
-- draft_picks/draft_rerolls, which are RLS-protected per user, so a caller
-- can only ever see their own run's state through it.
create or replace function public.draft_eligible_club_seasons(p_run_id bigint)
returns table (club_season_id bigint, club_name text, strength_quartile integer)
language sql
stable
as $$
    with run as (
        select cap_weak_spins, cap_elite_spins, max_same_club_season, league_id
        from public.draft_runs where id = p_run_id
    ),
    -- squads already used by this run, via the picks made from them
    spun as (
        select pss.club_season_id, count(*) as times
        from public.draft_picks dp
        join public.player_season_stats pss on pss.id = dp.player_season_stat_id
        where dp.run_id = p_run_id
        group by pss.club_season_id
    ),
    tier_usage as (
        select
            count(*) filter (where p.strength_quartile = 1) as weak_used,
            count(*) filter (where p.strength_quartile = 4) as elite_used
        from spun s
        join public.club_season_draft_pool p on p.club_season_id = s.club_season_id
    )
    select p.club_season_id, p.club_name, p.strength_quartile
    from public.club_season_draft_pool p
    join public.seasons se on se.id = p.season_id
    cross join run r
    cross join tier_usage tu
    left join spun s on s.club_season_id = p.club_season_id
    where se.league_id = r.league_id
      and coalesce(s.times, 0) < r.max_same_club_season
      and (p.strength_quartile <> 1 or tu.weak_used  < r.cap_weak_spins)
      and (p.strength_quartile <> 4 or tu.elite_used < r.cap_elite_spins);
$$;

comment on function public.draft_eligible_club_seasons(bigint) is
    'Club_seasons a draft run may still spin, honouring its weak/elite tier caps '
    'and same-squad limit. Pick uniformly at random from these — the caps do the '
    'balancing, so the draw itself stays unweighted (see migration header).';

grant execute on function public.draft_eligible_club_seasons(bigint) to authenticated, service_role;
