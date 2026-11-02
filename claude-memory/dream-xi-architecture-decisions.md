---
name: dream-xi-architecture-decisions
description: Dream XI app — resolved architecture/scope decisions from PROJECT_SPEC_v2.md §12
metadata: 
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-28T04:36:32.544Z
---

Dream XI (com.dreamxi.app) is an Android native app (Kotlin/Compose) — a
historical football draft & season-simulation game. Full spec lives in
`PROJECT_SPEC_v2.md` at repo root. As of 2026-08-28, all of §12's open
decisions were resolved with the user:

- **Backend:** Supabase (Postgres + Auth) — confirmed, not Firebase/custom.
- **Simulation engine:** standalone **Kotlin/Ktor** service, server-side —
  deliberately NOT a Supabase Edge Function (Deno/TS), to keep the whole
  stack in one language and share DTOs/tests with the Android app.
- **Auth flow:** guest draft allowed first (no account needed), prompt to
  sign up afterward to save it.
- **Fixture realism:** the drafted XI replaces the real **20th-place
  (last-place) club** for that season and plays that club's actual 38-game
  fixture schedule (19 opponents, home+away) against the other 19 real
  clubs. Final table is a real 20-team table regenerated with the XI's
  results swapped in. This means the ETL needs real fixture-by-fixture
  results per season, not just final league tables — heavier scrape scope.
- **Monetization:** none for v1 (free, no ads, no IAP).
- **Player photos/crests:** generic icons/initials only for v1 — no real
  images, avoids licensing risk.
- **Save-history:** unlimited runs per user, no cap.
- **Formations:** fixed templates for v1 (not custom drag-and-drop).
- **Trait tags:** rule-based thresholds (not a weighted/statistical model).
- **Added to stack beyond spec's original list:** Crashlytics or Sentry for
  crash reporting; Google Sign-In via Credential Manager API (not the
  deprecated GoogleSignInClient); Compose UI/Paparazzi screenshot tests
  given how much of the product's quality bar rides on animation (§10.4).

**Why this matters:** these were explicit open decision points in the spec
doc that the user made a judgment call on in conversation — not derivable
from the code or the doc's original text. Treat them as settled; don't
re-ask or re-litigate. See [[dream-xi-dev-setup]], [[dream-xi-design-language]].

**`PROJECT_OVERVIEW.md` (repo root, written 2026-09-01)** is a reverse-engineered
description of the app as it actually exists — data coverage, mechanics, every
engine constant, screen inventory, test list, known gaps, and a section of
things already tried and rejected with the evidence. It was written to hand to
other models for a second opinion. Keep it current when the engine changes, and
read it before re-deriving anything about the project's shape.
