-- ============================================================================
-- ERA XI — European Cup mode schema (Champions League format, 2009/10-2023/24;
-- 2023/24 loaded first).
--
-- Run this in the Supabase SQL Editor. Safe to re-run: every statement is
-- guarded, so applying it twice changes nothing.
--
-- THE DESIGN THESE TABLES ENCODE (agreed with the user):
--   * The user's XI REPLACES one of the eight clubs that finished BOTTOM of
--     their group, chosen by a draw among those eight — hence
--     ec_entries.finished_bottom.
--   * Real results stand for every match the user is not in, so real scorelines
--     are stored, not just fixtures.
--   * The draft pool covers every group-stage club's roster: a draft round spins
--     one club-season squad (e.g. "Inter Milan 2023/24"), so a squad is keyed by
--     (club, season) and is NOT owned by the tournament that season's entry
--     belongs to.
--
-- WHY NEW TABLES RATHER THAN REUSING THE LEAGUE ONES. The league tables are
-- shaped around two countries' leagues (a player belongs to a club_season with
-- box-score stats and a final league position). These squads carry a rating and a
-- position only, and the matches have legs, extra time and shootouts. Same
-- reasoning as migration_0007 for the World Cup.
--
-- STRENGTH. A club's strength lives on its squad row (the mean of its best 14
-- players, as the league mode defines it). A club with no roster (Red Star
-- Belgrade: EA licenses no Serbian league) still gets a squad row, with
-- has_roster = false, a strength ESTIMATED from its own results, and the
-- strength_source / strength_std_error that say so. It has no players, so it can
-- never be drawn in the draft spin, but it can still be the club the user replaces
-- and it still plays real matches as an opponent.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Reference data (public read; written only by the ETL's service_role key)
-- ----------------------------------------------------------------------------

create table if not exists public.ec_seasons (
    id bigint generated always as identity primary key,
    -- The year the season starts: 2023 is 2023/24.
    season_year integer not null unique,
    label text not null,
    -- 'groups_8x4' through 2023/24; the 2024/25 league phase will be a new value.
    format text not null,
    team_count integer not null
);

create table if not exists public.ec_clubs (
    id bigint generated always as identity primary key,
    name text not null unique
);

create table if not exists public.ec_squads (
    id bigint generated always as identity primary key,
    club_id bigint not null references public.ec_clubs (id) on delete cascade,
    season_id bigint not null references public.ec_seasons (id) on delete cascade,
    has_roster boolean not null,
    -- Mean rating of the club's best 14 players, on the same scale the league
    -- engine uses. Estimated (see header) where has_roster is false.
    team_strength numeric not null,
    -- 'squad_top14' or 'estimated_from_<n>_results'.
    strength_source text not null,
    strength_std_error numeric,
    unique (club_id, season_id)
);

create table if not exists public.ec_squad_players (
    id bigint generated always as identity primary key,
    squad_id bigint not null references public.ec_squads (id) on delete cascade,
    sofifa_id text,
    full_name text not null,
    nationality text,
    -- `overall` is the roster edition's own rating (FIFA 24 for 2023/24); `rating`
    -- is the one used, taken from the N+1 edition where the player could be linked
    -- and from the roster edition where not. rating_edition records which.
    overall integer not null,
    rating numeric not null,
    rating_edition text not null,
    positions text[] not null,
    birth_year integer,
    -- Per-position ratings from the roster edition (nullable: not every row has them).
    grid_cb integer,
    grid_fb integer,
    grid_dm integer,
    grid_cm integer,
    grid_cam integer,
    grid_winger integer,
    grid_st integer
);

create index if not exists ec_squad_players_squad_idx
    on public.ec_squad_players (squad_id);

create table if not exists public.ec_entries (
    id bigint generated always as identity primary key,
    season_id bigint not null references public.ec_seasons (id) on delete cascade,
    club_id bigint not null references public.ec_clubs (id),
    squad_id bigint not null references public.ec_squads (id),
    -- Group letter as published on the season's Wikipedia page, not reconstructed.
    group_label text not null,
    group_position integer not null,
    played integer,
    won integer,
    drawn integer,
    lost integer,
    goals_for integer,
    goals_against integer,
    points integer,
    -- 4th in the group: the eight clubs the takeover draw chooses between.
    finished_bottom boolean not null default false,
    -- Furthest stage reached: 'group', 'r16', 'qf', 'sf', 'final', 'winner'.
    reached_stage text not null,
    unique (season_id, club_id)
);

create index if not exists ec_entries_season_idx on public.ec_entries (season_id);
create index if not exists ec_entries_bottom_idx
    on public.ec_entries (season_id) where finished_bottom;

create table if not exists public.ec_matches (
    id bigint generated always as identity primary key,
    season_id bigint not null references public.ec_seasons (id) on delete cascade,
    stage text not null check (stage in ('group', 'r16', 'qf', 'sf', 'final')),
    group_label text,
    -- 1 or 2; a group match's leg is which meeting of the pair it is. The final is 1.
    leg integer not null,
    match_date date,
    -- Both entries must belong to `season_id`. Postgres cannot express that
    -- without a composite foreign key or a trigger; the loader enforces it.
    home_entry_id bigint not null references public.ec_entries (id) on delete cascade,
    away_entry_id bigint not null references public.ec_entries (id) on delete cascade,
    home_goals integer not null,
    away_goals integer not null,
    extra_time boolean not null default false,
    -- Only a tie that went to penalties has these.
    home_pens integer,
    away_pens integer,
    venue text,
    check (home_entry_id <> away_entry_id)
);

create index if not exists ec_matches_season_idx on public.ec_matches (season_id, stage);

-- ----------------------------------------------------------------------------
-- User-generated data (RLS: owner-only)
-- ----------------------------------------------------------------------------

create table if not exists public.ec_runs (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    season_id bigint not null references public.ec_seasons (id),
    -- The bottom-of-group club this XI takes over, set once the draw resolves.
    replaced_entry_id bigint references public.ec_entries (id),
    formation text not null,
    rerolls_allowed integer,
    created_at timestamptz not null default now()
);

create table if not exists public.ec_picks (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.ec_runs (id) on delete cascade,
    slot_position text not null,
    squad_player_id bigint not null references public.ec_squad_players (id),
    round_number integer not null,
    unique (run_id, slot_position)
);

create index if not exists ec_picks_run_idx on public.ec_picks (run_id);

create table if not exists public.ec_results (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.ec_runs (id) on delete cascade,
    -- The furthest stage reached, same labels as ec_entries.reached_stage.
    reached_stage text,
    champion boolean not null default false,
    played integer,
    won integer,
    drawn integer,
    lost integer,
    goals_for integer,
    goals_against integer,
    report_json jsonb,
    created_at timestamptz not null default now()
);

-- ============================================================================
-- Row Level Security
-- ============================================================================

alter table public.ec_seasons enable row level security;
alter table public.ec_clubs enable row level security;
alter table public.ec_squads enable row level security;
alter table public.ec_squad_players enable row level security;
alter table public.ec_entries enable row level security;
alter table public.ec_matches enable row level security;
alter table public.ec_runs enable row level security;
alter table public.ec_picks enable row level security;
alter table public.ec_results enable row level security;

-- Reference data: public read-only. No write policy exists for anon or
-- authenticated, so the Data API rejects their writes; only service_role
-- (which bypasses RLS) can load these.
drop policy if exists "Public read access" on public.ec_seasons;
create policy "Public read access" on public.ec_seasons for select using (true);
drop policy if exists "Public read access" on public.ec_clubs;
create policy "Public read access" on public.ec_clubs for select using (true);
drop policy if exists "Public read access" on public.ec_squads;
create policy "Public read access" on public.ec_squads for select using (true);
drop policy if exists "Public read access" on public.ec_squad_players;
create policy "Public read access" on public.ec_squad_players for select using (true);
drop policy if exists "Public read access" on public.ec_entries;
create policy "Public read access" on public.ec_entries for select using (true);
drop policy if exists "Public read access" on public.ec_matches;
create policy "Public read access" on public.ec_matches for select using (true);

-- User data: owner-only, mirroring wc_runs / wc_picks / wc_results.
drop policy if exists "Users manage their own european cup runs" on public.ec_runs;
create policy "Users manage their own european cup runs" on public.ec_runs
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "Users manage their own european cup picks" on public.ec_picks;
create policy "Users manage their own european cup picks" on public.ec_picks
    for all
    using (
        exists (select 1 from public.ec_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.ec_runs r where r.id = run_id and r.user_id = auth.uid())
    );

drop policy if exists "Users manage their own european cup results" on public.ec_results;
create policy "Users manage their own european cup results" on public.ec_results
    for all
    using (
        exists (select 1 from public.ec_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.ec_runs r where r.id = run_id and r.user_id = auth.uid())
    );

-- ============================================================================
-- Grants
--
-- Required on top of RLS: "Automatically expose new tables" is OFF for this
-- project, so a new table starts with no privileges for ANY role — service_role
-- included (see grants_service_role.sql and migration_0007).
-- ============================================================================

grant select on
    public.ec_seasons,
    public.ec_clubs,
    public.ec_squads,
    public.ec_squad_players,
    public.ec_entries,
    public.ec_matches
to anon, authenticated;

grant select, insert, update, delete on
    public.ec_runs,
    public.ec_picks,
    public.ec_results
to authenticated;

grant all on
    public.ec_seasons,
    public.ec_clubs,
    public.ec_squads,
    public.ec_squad_players,
    public.ec_entries,
    public.ec_matches,
    public.ec_runs,
    public.ec_picks,
    public.ec_results
to service_role;

grant usage, select on all sequences in schema public to service_role;
