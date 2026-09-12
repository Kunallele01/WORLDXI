-- ============================================================================
-- ERA XI — World Cup mode schema (editions 2006-2026).
--
-- Run this in the Supabase SQL Editor. Safe to re-run: every statement is
-- guarded, so applying it twice changes nothing.
--
-- WHY NEW TABLES RATHER THAN REUSING THE CLUB ONES. Everything loaded so far is
-- club-season shaped: a player belongs to a club_season, and a fixture joins two
-- club_seasons. International football keys on (nation, FIFA edition) instead,
-- has no league table, and its matches carry rounds, extra time and shootouts.
-- Forcing it through club_seasons would mean a fake league per tournament.
--
-- THE DESIGN THESE TABLES ENCODE (agreed with the user):
--   * The user's XI REPLACES a nation that finished bottom of its group, drawn
--     from the union of those nations — hence wc_nation_entries.finished_bottom.
--   * Real results stand for every match the user is not in, so the real
--     scorelines are stored, not just the fixtures.
--   * The draft pool is cross-era: any nation, any edition. So a squad is keyed
--     by (nation, rating_edition) and is NOT owned by a tournament.
--
-- WHY A SQUAD IS SEPARATE FROM AN ENTRY. A nation's anchor edition (WC 2026 ->
-- FC 26) sometimes holds too few rated players to field anyone, because EA only
-- rates licensed leagues and that coverage moved: Japan is 9 players in FIFA 07
-- and 472 in FIFA 19. Such a nation borrows the nearest edition holding eleven,
-- and wc_nation_entries.squad_id records WHICH squad was actually used, so a
-- borrow is visible rather than disguised as the anchor.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Reference data (public read; written only by the ETL's service_role key)
-- ----------------------------------------------------------------------------

create table if not exists public.wc_tournaments (
    id bigint generated always as identity primary key,
    year integer not null unique,
    team_count integer not null,
    -- The FIFA edition anchoring this tournament under the N+1 rule, as text
    -- because editions are written '07'..'26' everywhere else in the pipeline.
    rating_edition text not null
);

create table if not exists public.wc_nations (
    id bigint generated always as identity primary key,
    -- The RATINGS TABLE's spelling, which is the one squads are looked up by.
    -- Note EA renames countries between editions ("Czechia" in FIFA 07 and
    -- FC 26, "Czech Republic" in FIFA 15); this holds the canonical one and
    -- etl/wc_nations.py owns the mapping.
    name text not null unique
);

create table if not exists public.wc_squads (
    id bigint generated always as identity primary key,
    nation_id bigint not null references public.wc_nations (id) on delete cascade,
    rating_edition text not null,
    unique (nation_id, rating_edition)
);

create table if not exists public.wc_squad_players (
    id bigint generated always as identity primary key,
    squad_id bigint not null references public.wc_squads (id) on delete cascade,
    sofifa_id text,
    full_name text not null,
    -- `overall` is what the edition shipped; `rating` is that value moved onto
    -- the FIFA 17+ scale (etl/rating_scale.py), because EA inflated ratings
    -- between FIFA 15 and 17 and this pool deliberately mixes eras.
    overall integer not null,
    rating numeric not null,
    positions text[] not null,
    club text,
    birth_year integer
);

create index if not exists wc_squad_players_squad_idx
    on public.wc_squad_players (squad_id);

create table if not exists public.wc_nation_entries (
    id bigint generated always as identity primary key,
    tournament_id bigint not null references public.wc_tournaments (id) on delete cascade,
    nation_id bigint not null references public.wc_nations (id),
    -- The squad actually used for this entry: the tournament's anchor edition
    -- normally, a borrowed one where the anchor could not field eleven.
    squad_id bigint references public.wc_squads (id),
    -- How the fixture source spelled it, which is not always the nation's
    -- canonical name — 2006 fielded "Serbia & Montenegro", whose players the
    -- ratings table lists under Serbia.
    fixture_name text not null,
    -- RECONSTRUCTED, not sourced: neither fixture source labels the groups, so
    -- they are recovered as cliques of the group-stage opponent graph and
    -- lettered deterministically. The letters therefore need not match the real
    -- tournament's lettering; the membership does.
    group_label text,
    group_position integer,
    played integer,
    won integer,
    drawn integer,
    lost integer,
    goals_for integer,
    goals_against integer,
    points integer,
    -- Drives the takeover draw. Stored rather than derived so the draw does not
    -- have to recompute six tournaments of group tables on device.
    finished_bottom boolean not null default false,
    unique (tournament_id, nation_id)
);

create index if not exists wc_nation_entries_tournament_idx
    on public.wc_nation_entries (tournament_id);
create index if not exists wc_nation_entries_bottom_idx
    on public.wc_nation_entries (tournament_id) where finished_bottom;

create table if not exists public.wc_matches (
    id bigint generated always as identity primary key,
    tournament_id bigint not null references public.wc_tournaments (id) on delete cascade,
    -- 'Group stage', 'Round of 32' (2026 only), 'Round of 16', 'Quarter-finals',
    -- 'Semi-finals', 'Third-place match', 'Final'.
    round text not null,
    match_date date,
    -- Both entries must belong to `tournament_id`. Postgres cannot express that
    -- without a composite foreign key or a trigger; the loader enforces it and
    -- etl/verify_wc_staged.py re-checks it.
    home_entry_id bigint not null references public.wc_nation_entries (id) on delete cascade,
    away_entry_id bigint not null references public.wc_nation_entries (id) on delete cascade,
    home_goals integer,
    away_goals integer,
    -- Only a knockout tie that went to penalties has these.
    home_pens integer,
    away_pens integer,
    shootout_winner_entry_id bigint references public.wc_nation_entries (id),
    check (home_entry_id <> away_entry_id)
);

create index if not exists wc_matches_tournament_idx
    on public.wc_matches (tournament_id, round);

-- ----------------------------------------------------------------------------
-- User-generated data (RLS: owner-only)
-- ----------------------------------------------------------------------------

create table if not exists public.wc_runs (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    tournament_id bigint not null references public.wc_tournaments (id),
    -- The bottom-of-group nation this XI takes over, set once the draw resolves.
    replaced_entry_id bigint references public.wc_nation_entries (id),
    formation text not null,
    created_at timestamptz not null default now()
);

create table if not exists public.wc_picks (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.wc_runs (id) on delete cascade,
    slot_position text not null,
    squad_player_id bigint not null references public.wc_squad_players (id),
    round_number integer not null,
    unique (run_id, slot_position)
);

create index if not exists wc_picks_run_idx on public.wc_picks (run_id);

create table if not exists public.wc_results (
    id bigint generated always as identity primary key,
    run_id bigint not null references public.wc_runs (id) on delete cascade,
    -- The furthest round reached, using the same labels as wc_matches.round.
    reached_round text,
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

alter table public.wc_tournaments enable row level security;
alter table public.wc_nations enable row level security;
alter table public.wc_squads enable row level security;
alter table public.wc_squad_players enable row level security;
alter table public.wc_nation_entries enable row level security;
alter table public.wc_matches enable row level security;
alter table public.wc_runs enable row level security;
alter table public.wc_picks enable row level security;
alter table public.wc_results enable row level security;

-- Reference data: public read-only. No write policy exists for anon or
-- authenticated, so the Data API rejects their writes; only service_role
-- (which bypasses RLS) can load these.
drop policy if exists "Public read access" on public.wc_tournaments;
create policy "Public read access" on public.wc_tournaments for select using (true);
drop policy if exists "Public read access" on public.wc_nations;
create policy "Public read access" on public.wc_nations for select using (true);
drop policy if exists "Public read access" on public.wc_squads;
create policy "Public read access" on public.wc_squads for select using (true);
drop policy if exists "Public read access" on public.wc_squad_players;
create policy "Public read access" on public.wc_squad_players for select using (true);
drop policy if exists "Public read access" on public.wc_nation_entries;
create policy "Public read access" on public.wc_nation_entries for select using (true);
drop policy if exists "Public read access" on public.wc_matches;
create policy "Public read access" on public.wc_matches for select using (true);

-- User data: owner-only, mirroring the club mode's draft_runs / draft_picks.
drop policy if exists "Users manage their own world cup runs" on public.wc_runs;
create policy "Users manage their own world cup runs" on public.wc_runs
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "Users manage their own world cup picks" on public.wc_picks;
create policy "Users manage their own world cup picks" on public.wc_picks
    for all
    using (
        exists (select 1 from public.wc_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.wc_runs r where r.id = run_id and r.user_id = auth.uid())
    );

drop policy if exists "Users manage their own world cup results" on public.wc_results;
create policy "Users manage their own world cup results" on public.wc_results
    for all
    using (
        exists (select 1 from public.wc_runs r where r.id = run_id and r.user_id = auth.uid())
    )
    with check (
        exists (select 1 from public.wc_runs r where r.id = run_id and r.user_id = auth.uid())
    );

-- ============================================================================
-- Grants
--
-- Required on top of RLS: "Automatically expose new tables" is OFF for this
-- project, so a new table starts with no privileges for ANY role — service_role
-- included, which is what made the ETL's first inserts fail with a bare
-- "permission denied" before RLS even came into it (see grants_service_role.sql).
-- ============================================================================

grant select on
    public.wc_tournaments,
    public.wc_nations,
    public.wc_squads,
    public.wc_squad_players,
    public.wc_nation_entries,
    public.wc_matches
to anon, authenticated;

grant select, insert, update, delete on
    public.wc_runs,
    public.wc_picks,
    public.wc_results
to authenticated;

grant all on
    public.wc_tournaments,
    public.wc_nations,
    public.wc_squads,
    public.wc_squad_players,
    public.wc_nation_entries,
    public.wc_matches,
    public.wc_runs,
    public.wc_picks,
    public.wc_results
to service_role;

grant usage, select on all sequences in schema public to service_role;
