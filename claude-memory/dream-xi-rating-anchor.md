---
name: dream-xi-rating-anchor
description: "overall_rating is anchored to per-season FIFA/EA-FC ratings, NOT derived from box-score stats — defensive stats are provably inverted"
metadata:
  node_type: memory
  type: project
  originSessionId: e7718f35-474b-4d99-9b30-276d5f25aa25
  modified: 2026-08-28T22:51:56.685Z
---

`overall_rating` is **not** derived from football stats. It is anchored to
the player's per-season FIFA/EA-FC rating, with box-score stats demoted to a
small, bounded season-form modifier on top.

**Why — this is settled, do not re-litigate by "tuning weights".** Measured
across all 10 loaded league-seasons, for CBs with 1800+ minutes:

    CBs at the top-5 defenses     mean 1.88 tackles+interceptions /90
    CBs at mid-table defenses     mean 2.03
    CBs at the bottom-5 defenses  mean 2.17

Defensive volume is **inversely correlated** with defensive quality — elite
centre-backs make fewer defensive actions because they position well. No
reweighting of an inverted signal can produce a correct rating. Two full
attempts failed before this was measured (a z-score normalization that broke
on right-skewed stats, and a midpoint-centred delta that inflated every good
player); both are documented as dead code + docstrings in
`etl/attributes.py` so they don't get retried.

**The blend** (see attributes.py "bias fix, part 3"):

    form_pct     = percentile of the player's stats score within their group
    expected_pct = percentile of their FIFA base within the same group
    confidence   = min(1, minutes / 1800)
    delta        = clamp((form_pct - expected_pct)/50 * swing * confidence)
    overall      = fifa_base + delta

Both sides are percentiles over the same population deliberately — centring
on the league midpoint instead handed every good player the full positive
swing just for being good (Lewandowski 91 → 98). `swing` is per position
group (FW 8, MF 5, GK 4, DF 3), scaled by how much the stats actually tell
us about that role — the DF signal is the proven-inverted one, so it barely
votes. A 4-game hot streak before an injury moves a rating ~1 point, not 30.

**Data source — use ONLY `etl/archive/male_players.csv`** (Kaggle,
stefanoleone992 "EA Sports FC 24 complete player dataset"). One file, every
edition FIFA 15→FC 24 keyed by `fifa_version`, with `long_name` + `dob` +
`overall` populated throughout. EA FC 25 is NOT in it and is loaded
separately from `etl/29 Aug Files/male_players.csv` (weaker schema: short
names + age, no dob).

**Edition → season mapping, corrected 2026-08-29 and MEASURED.** Editions
ship in September rated on what players had JUST DONE, so **edition N+1
describes season N** (FIFA 21 → 2019/20 … EA FC 25 → 2023/24). The original
N → N mapping was off by one. Correlation of squad strength with actual
final league position: **-0.871 under N+1 vs -0.737 under N**, in every
season without exception.

**Each season has a fallback edition — `fifa_lookup.SEASON_EDITIONS`.** An
N+1-only mapping loses every player who left top-flight football at the end
of that season, since he is absent from the next edition. That produced 52
regulars (900+ min) rated under 60 — Mariappa 34, Garay 37, Diego Costa 42,
Lingard 42. Prefer N+1, accept N, never neither.

**TIER order beats EDITION order in the cascade.** All strict tiers
(exact/subset/year±1) run against the whole edition chain before any fuzzy
tier runs at all. Edition-major ordering let a *different* "B. Foster" in
FIFA 23 win Ben Foster's slot at OVR 58 on the initial-form tier, beating an
exact full-name match in FIFA 22 (his real 78). A fuzzy hit on the better
edition is never worth an exact hit on a slightly worse one.

**Do NOT stitch together GitHub mirrors.** A first pass did, and two of the
four files had ZERO `long_name` and ZERO `dob` (just "L. Dunk" + an age).
That produced: unresolvable identities, La Liga 2022/23 coverage of 77%, and
a silent false match binding Lewis Dunk (absent from that dump) onto
Harrison Dunk, rated 64. Verify a source contains the identity fields you
depend on before building matching on it.

**Coverage: 98.9%** of 5,723 player-seasons, only **4 unmatched regulars**
(900+ min): Rigoni, Dani Alves, Pape Gueye, Sokratis. Verified absent from
every edition of the source — not fixable by better matching. Unmatched rows
fall back to the pure-stats rating tagged `rating_source="stats"` and are
listed in `etl/fifa/unmatched_players.csv`; exactly 2 players in the whole
pool are now rated under 60 with 900+ minutes. Verified sane: Van Dijk
88-90, Haaland 88→91, Messi 94, Lewandowski 90-91, Son 87-89, Dunk 77-79,
Ben Foster 78.

**Matching lives in `etl/fifa_lookup.py`** as a tier cascade
(exact → subset → year±1 → name → initial → family-first → surname → club),
reported per-tier by `etl/fifa_match_report.py`, which calls the SAME
`get_with_tier()` rather than duplicating the cascade (an earlier copy
drifted and under-reported). Two traps already hit and fixed: the surname
tier bound "Son Heung-min" to "Song Seung Min" (fixed by ordering
family-first ahead of it), and the club tier was gated behind
`len(parts) >= 2`, silently skipping every mononym — Kiké, Djené, Joselu —
which is exactly who it exists to rescue.

See [[dream-xi-attribute-formula-spec]] and [[dream-xi-etl-pilot]].
