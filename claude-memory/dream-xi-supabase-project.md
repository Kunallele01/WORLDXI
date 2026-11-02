---
name: dream-xi-supabase-project
description: "Dream XI's Supabase project is created and wired into the Android app"
metadata: 
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-28T06:05:54.756Z
---

The Supabase project for Dream XI exists: project ref `tpppanjcynvislrjphdd`
(URL `https://tpppanjcynvislrjphdd.supabase.co`), created 2026-08-28. Project
settings used: Data API enabled, "automatically expose new tables" disabled,
automatic RLS enabled (every new table starts RLS-protected).

- Project URL + anon key live in `local.properties` (gitignored) as
  `SUPABASE_URL` / `SUPABASE_ANON_KEY`, read into `BuildConfig` by
  `app/build.gradle.kts`. Do not hardcode them anywhere or commit them.
- The `service_role` key has never been shared and must never enter the
  Android app — it's ETL-only (§6.3 of PROJECT_SPEC_v2.md), on the user's
  machine/CI, never on-device.
- Schema + RLS policies live at `supabase/schema.sql` in the repo — already
  applied via the SQL Editor (2026-08-28), and verified working (anon reads
  reference tables, gets 401 on owner-only tables). Covers all of §9's
  tables plus two additions made after the fixture-realism decision: a
  `fixtures` table (real match-by-match results) and
  `draft_runs.replaced_club_season_id`. A follow-up file,
  `supabase/grants_service_role.sql`, was also needed and applied — disabling
  "automatically expose new tables" during project setup withheld grants
  from `service_role` too, not just `anon`/`authenticated`, which blocked the
  ETL's writes with a bare permission-denied until granted explicitly.
- `SupabaseClient` DI wiring: `app/src/main/java/com/dreamxi/app/di/SupabaseModule.kt`
  (Auth + Postgrest + Realtime installed; no Functions plugin — simulation
  runs on a separate Kotlin/Ktor service per [[dream-xi-architecture-decisions]]).

**Why this matters:** if the schema needs to change, edit `supabase/schema.sql`
and hand the user the diff/new statements to run in the SQL Editor — this
session has no direct DB credentials (by design) and can't apply migrations
itself. See [[dream-xi-architecture-decisions]], [[dream-xi-dev-setup]].
