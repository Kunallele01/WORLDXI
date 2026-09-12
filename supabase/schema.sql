-- ============================================================================
-- Dream XI — initial Supabase schema (PROJECT_SPEC_v2.md §9)
--
-- Run this once in the Supabase dashboard: Project > SQL Editor > New query,
-- paste this whole file, and click Run. Safe to re-run only if you drop the
-- tables first — this does not use "if not exists" guards, so re-running
-- against an already-applied schema will error on the second run.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Reference data (public read-only from the app; written only by the ETL's
-- service_role key, which bypasses RLS entirely — never used on-device)
-- ----------------------------------------------------------------------------

create table public.leagues (
    id bigint generated always as identity primary key,
    name text not null,
    country text not null,
    default_tier text not null check (default_tier in ('A', 'B', 'C'))
);

create table public.seasons (
    id bigint generated always as identity primary key,
    league_id bigint not null references public.leagues (id) on delete cascade,
    label text not null,               -- e.g. '2024/25'
    start_year integer not null,
    data_tier text not null check (data_tier in ('A', 'B', 'C')),
    unique (league_id, start_year)
);

create table public.club_seasons (
    id bigint generated always as identity primary key,
    season_id bigint not null references public.seasons (id) on delete cascade,
    club_name text not null,
    final_position integer not null,
    wins integer not null,
    draws integer not null,
    losses integer not null,
    goals_for integer not null,
    goals_against integer not null,
    points integer not null,
    unique (season_id, club_name)
);

-- Real fixture-by-fixture results for a season — needed because the drafted
-- XI replaces the real last-place club and plays that club's actual 38-game
-- schedule (§8.1), not a synthetic round-robin.
create table public.fixtures (
    id bigint generated always as identity primary key,
    season_id bigint not null references public.seasons (id) on delete cascade,
    matchday integer,
    home_club_season_id bigint not null references public.club_seasons (id) on delete cascade,
    away_club_season_id bigint not null references public.club_seasons (id) on delete cascade,
    home_goals integer not null,
    away_goals integer not null
);

create table public.players (
    id bigint generated always as identity primary key,
    full_name text not null,
    nationality text,
    date_of_birth date
);

create table public.player_season_stats (
    id bigint generated always as identity primary key,
    player_id bigint not null references public.players (id) on delete cascade,
    club_season_id bigint not null references public.club_seasons (id) on delete cascade,
    primary_position text not null,
    secondary_positions text[],
    appearances integer,
    minutes integer,
    goals integer,
    assists integer,
    yellow_cards integer,
    red_cards integer,
    shots integer,
    shots_on_target integer,
    key_passes integer,
    pass_completion_pct numeric,
    tackles integer,
    interceptions integer,
    clearances integer,
    aerial_won integer,
    aerial_lost integer,
    dribbles_completed integer,
    saves integer,
    save_pct numeric,
    clean_sheets integer,
    xg numeric,
    xa numeric,
    progressive_carries integer,
    progressive_passes integer,
    -- computed at ETL time (§7) — the app never computes these on-device:
    finishing integer,
    creation integer,
    carrying integer,
    buildup integer,
    defense integer,
    physical integer,
    overall_rating integer,
    traits jsonb
);

-- ----------------------------------------------------------------------------
-- User-generated data (RLS: owner-only read/write)
-- ----------------------------------------------------------------------------

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    display_name text,
    created_at timestamptz not null default now()
);

create table public.draft_runs (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    league_id bigint not null references public.leagues (id),
    season_scope text not null,        -- a specific season label, or 'random'
    formation text not null,
    -- the real club this run's XI replaces for simulation (§8.1) — set once
    -- the season/league scope resolves to an actual season and its last-place club
    replaced_club_season_id bigint references public.club_seasons (id),
    created_at timestamptz not null default now()
);

create table public.draft_picks (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.draft_runs (id) on delete cascade,
    slot_position text not null,
    player_season_stat_id bigint not null references public.player_season_stats (id),
    round_number integer not null
);

create table public.simulation_results (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.draft_runs (id) on delete cascade,
    simulated_season_id bigint not null references public.seasons (id),
    final_position integer,
    points integer,
    wins integer,
    draws integer,
    losses integer,
    gf integer,
    ga integer,
    report_json jsonb,
    created_at timestamptz not null default now()
);

-- ============================================================================
-- Row Level Security
-- ============================================================================

alter table public.leagues enable row level security;
alter table public.seasons enable row level security;
alter table public.club_seasons enable row level security;
alter table public.fixtures enable row level security;
alter table public.players enable row level security;
alter table public.player_season_stats enable row level security;
alter table public.profiles enable row level security;
alter table public.draft_runs enable row level security;
alter table public.draft_picks enable row level security;
alter table public.simulation_results enable row level security;

-- Reference data: public read-only. No insert/update/delete policy is
-- defined for the anon/authenticated roles, so the Data API rejects writes;
-- only the ETL's service_role key (which bypasses RLS) can write these.
create policy "Public read access" on public.leagues for select using (true);
create policy "Public read access" on public.seasons for select using (true);
create policy "Public read access" on public.club_seasons for select using (true);
create policy "Public read access" on public.fixtures for select using (true);
create policy "Public read access" on public.players for select using (true);
create policy "Public read access" on public.player_season_stats for select using (true);

-- User data: owner-only.
create policy "Users manage their own profile" on public.profiles
    for all using (auth.uid() = id) with check (auth.uid() = id);

create policy "Users manage their own draft runs" on public.draft_runs
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "Users manage their own draft picks" on public.draft_picks
    for all using (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    );

create policy "Users manage their own simulation results" on public.simulation_results
    for all using (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.draft_runs r where r.id = run_id and r.user_id = auth.uid())
    );

-- ============================================================================
-- Auto-create a profile row whenever a new auth user signs up (§5.4 guest ->
-- account flow ends with a real Supabase Auth user, which should always get
-- a profiles row without the app having to remember to insert one itself).
-- ============================================================================

create function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, new.raw_user_meta_data ->> 'display_name');
  return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute procedure public.handle_new_user();
