-- ============================================================================
-- ERA XI — real goalscorers for World Cup matches (editions 2006-2026).
--
-- Run this in the Supabase SQL Editor, after migration_0007. Safe to re-run:
-- every statement is guarded.
--
-- WHY. The user chose to show who REALLY scored in every match his XI does not
-- play, so a real match reads "Quiñones 9', Jiménez 67'" rather than a bare
-- 2-0, and the tournament's top-scorer table is real history plus whatever his
-- run changes. 1,112 goals across all 424 matches, parsed from Wikipedia's match
-- pages by etl/wc_wikipedia.py — which refuses to write unless every match's
-- goals add up to its real score on both sides.
--
-- ATTRIBUTION IS REQUIRED. The source is Wikipedia under CC BY-SA 4.0, which
-- permits this reuse ON CONDITION of attribution. Any screen showing these
-- scorers must credit it ("Match data: Wikipedia, CC BY-SA 4.0").
--
-- Group letters need no schema change: wc_nation_entries.group_label is
-- reloaded with the REAL letters from the same pages, replacing the
-- date-ordered guesses migration_0007 described.
-- ============================================================================

-- HOSTS SCORE MORE. Measured over every 2006-2026 host match decided in ninety
-- minutes (etl/wc_calibration.py): a host scores x1.42 what the model expects
-- (likelihood ratio 7.12, significant); it concedes x1.15, which is not
-- significant and is not applied. The engine needs to know who hosted.
alter table public.wc_nation_entries
    add column if not exists is_host boolean not null default false;

create table if not exists public.wc_match_goals (
    id bigint generated always as identity primary key,
    match_id bigint not null references public.wc_matches (id) on delete cascade,
    -- The side the goal COUNTS for. For an own goal that is the opponent of the
    -- man who put it in, which is how every scoresheet lists it.
    entry_id bigint not null references public.wc_nation_entries (id) on delete cascade,
    scorer text not null,
    -- 1-90 in normal time, 91-120 in extra time. Stoppage time is kept apart
    -- (45+2 is minute 45, stoppage 2) so goals still sort in the order scored.
    minute integer not null check (minute between 1 and 120),
    stoppage integer check (stoppage is null or stoppage > 0),
    kind text not null check (kind in ('goal', 'penalty', 'own_goal'))
);

create index if not exists wc_match_goals_match_idx on public.wc_match_goals (match_id);

alter table public.wc_match_goals enable row level security;

drop policy if exists "Public read access" on public.wc_match_goals;
create policy "Public read access" on public.wc_match_goals for select using (true);

-- "Automatically expose new tables" is off for this project, so a new table has
-- no privileges for any role until granted — service_role included.
grant select on public.wc_match_goals to anon, authenticated;
grant all on public.wc_match_goals to service_role;
grant usage, select on all sequences in schema public to service_role;
