---
name: dream-xi-attribute-formula-spec
description: "Dream XI's attribute formula is now a written spec, not just code — ATTRIBUTE_FORMULA_SPEC.md"
metadata: 
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-28T22:52:24.825Z
---

PROJECT_SPEC_v2.md §7.3 called for a dedicated "literal stat → 0-99 mapping"
reference doc before/alongside the ETL's attribute code. That doc now exists
at `D:\Dev\FootballSim\ATTRIBUTE_FORMULA_SPEC.md`, written after the pilot
ETL ([[dream-xi-etl-pilot]]) so it reflects real data availability instead
of the original spec's assumptions.

Key decisions captured there (don't re-litigate without reading it first):
- Two separate position-grouping systems: 4 coarse groups (GK/DF/MF/FW) for
  attribute percentile fairness, vs. 8 specific role groups (GK/CB/FB/DM/CM/
  CAM/Winger/ST, from Transfermarkt) for draft slot eligibility — these are
  intentionally different granularities for different purposes.
- Explicitly marks Carrying and Build-up attributes as weak proxies (no real
  possession data exists anymore) and documents known biases in Defense
  (rewards busy/reactive defenders over composed ones) and GK Defense
  (penalizes keepers behind strong defenses).
- Lists open questions for next iteration: excluding low-minute players from
  the comparison population, validating trait thresholds against real
  distributions, checking Understat.com as an xG source, and untested
  pre-2010 data coverage.

**SUPERSEDED IN PART, 2026-08-29 — read [[dream-xi-rating-anchor]] first.**
The spec's §4.5/§4.6 treat the Defense and GK-Defense biases as regrettable
but tolerable, and §5 derives `overall_rating` from the attributes. Both are
now wrong in the parts that matter:
- The defensive bias isn't a rough edge, it's an *inversion* — measured, CBs
  at the best defenses post LOWER tackle+interception volume than CBs at the
  worst. A stat that points backwards can't be reweighted into correctness.
- `overall_rating` is therefore no longer computed from these attributes at
  all. It is anchored to the player's per-season FIFA/EA-FC rating, with the
  stats reduced to a small bounded form modifier.
The six sub-attributes (finishing/creation/carrying/buildup/defense/
physical) are still stat-derived and still follow this spec — only the
headline rating changed. ATTRIBUTE_FORMULA_SPEC.md itself still needs
rewriting to match; treat its §5 as stale until then.

**Why this matters:** if asked to tune the formula, add a season, or extend
to a new league, read this doc first — it's the design intent, not just
what `etl/attributes.py` happens to compute today. See
[[dream-xi-architecture-decisions]] and [[dream-xi-rating-anchor]].
