# Dream XI — Attribute & Rating Formula Spec

> Authoritative reference for PROJECT_SPEC_v2.md §7.3: "the literal stat →
> 0-99 mapping per position group per tier." This supersedes §7's original
> assumptions about data richness — see §0 below for why.

---

## 0. Why this doc differs from the original §4/§7 assumptions

PROJECT_SPEC_v2.md §4 assumed a "Tier A (rich)" band (~2017–2025) with xG,
xA, progressive actions, aerial duel data, etc. On **2026-01-20**, FBref's
advanced-stats data provider terminated their license; FBref deleted that
data site-wide (confirmed via FBref's own blog post — see
`dream-xi-etl-pilot` memory / the pilot session's findings). No subscription
(including Stathead) restores it. This is permanent, not a paywall.

**What FBref still gives us, free, for any season it covers:**
goals, assists, appearances, minutes, cards, shots, shots-on-target,
tackles-won + interceptions (not full tackles/blocks/clearances — those are
gone too), and full goalkeeper stats (saves, save%, clean sheets, GA).

Everything below is designed around that reality, not the original
ambition. If a richer free source appears later (Understat for xG is the
most promising candidate), attributes marked **[proxy]** below are the ones
to upgrade first.

> **2026-08-29 correction — check "this data is gone" claims yourself.**
> `interceptions` was believed lost and was being dropped by the ETL. It is
> not lost; it is populated on FBref's live defense table (Van Dijk 2023/24:
> tackles_won 23, interceptions 35). Eight seasons had been loaded with it
> discarded, and in the two 2023/24 pilot files the column header
> (`90s Tkl TklW`) disagreed with the actual data (`90s TklW Int`), so
> interceptions were stored *as* tackles_won since the pilot. Both fixed and
> all 10 seasons re-verified. The lost-data list above is otherwise accurate,
> but treat it as a claim to re-verify against the live source before
> building logic that depends on it — this one propagated unchecked through
> several sessions.

---

## 1. Position groups — two separate systems, not one

These serve different purposes and deliberately use different granularity.

### 1.1 Percentile-comparison groups (4) — for computing the 0-99 numbers fairly
`GK`, `DF`, `MF`, `FW` — derived directly from FBref's own coarse position
tag (first-listed value when a player has multiple, e.g. `FW,MF` → `FW`).
Purpose: give a player a fair peer group to be percentile-ranked against
("is this a lot of tackles *for a forward*?"). Coarse is fine here — it
doesn't affect gameplay, only how generously a stat translates to a 0-99
number.

### 1.2 Role groups (8) — for draft slot eligibility
`GK`, `CB`, `FB`, `DM`, `CM`, `CAM`, `Winger`, `ST` — sourced from
Transfermarkt squad pages (one page per club, fuzzy-matched by player name
to the FBref row), **not** derivable from FBref's own tags. This is what
determines which formation slot (§10.6 Setup screen) a player-season is
eligible to fill during the draft (§2). See `etl/roles.py`. ~99% of players
per pilot season match directly; the rest fall back to a coarse GK→GK,
DF→CB, MF→CM, FW→ST guess so nothing is ever unassigned.

**These two systems intentionally disagree in granularity.** A CB and an FB
both get percentile-ranked against the same `DF` population (1.1), but are
different, non-interchangeable slots during the draft (1.2).

---

## 2. Data availability by field (post-Jan-2026 reality)

| Field | Source table | Availability |
|---|---|---|
| goals, assists, appearances, minutes, starts, cards | FBref Standard | Always |
| shots, shots-on-target | FBref Shooting | Always |
| tackles won, interceptions | FBref Defense | Always (full tackles/blocks/clearances: **gone**) |
| saves, save%, clean sheets, goals-against | FBref Keepers | Always, GK only |
| xG, xA, progressive carries/passes, touches, take-ons, passing accuracy | — | **Gone** (was the Tier A signal) |
| specific position role (CB vs FB, DM vs CAM, etc.) | Transfermarkt squad page | Always, but needs the per-club cross-reference (§1.2) |

There is effectively **one data tier now**, not three. §4's Tier A/B/C
distinction still matters for *older* seasons (pre-2000s data will have
even sparser stats — no shots/tackles breakdowns at all in some cases), but
the Tier A/B boundary as originally conceived (advanced vs basic stats) no
longer exists as a live option. Treat "what FBref gives free today" as the
de facto Tier B, and plan for a real Tier C fallback (goals/assists/apps/
cards only) for pre-2010-ish seasons once that data is pulled and its
actual field coverage is checked.

---

## 3. Normalization method

For every attribute below: **percentile rank within (this season, this
league, this §1.1 group)**, among all players in that group, using
`etl/attributes.py`'s `_percentile()` — a strict less-than-or-equal count
turned into a 0-99 rank. This directly implements §7.2's "normalize
within-season, within-league, within-position-group" principle.

`MIN_MINUTES` (450, ~5 full matches) exists as a documented threshold but
is **not currently used to exclude anyone** from the population — per
§7.2's graceful-degradation rule, a low-minutes player still gets ranked
against the same distribution rather than being left blank. (Open question,
§7 below: should low-minute players be excluded from the population that
*other* players are ranked against, to avoid a small-sample player's
extreme per-90 rate skewing the curve? Not yet decided.)

---

## 4. The six attributes

### 4.1 Finishing
- **Non-GK:** percentile of `goals per 90` within the §1.1 group.
- **GK:** not applicable — fixed low value (20). A goalkeeper's Finishing
  attribute is a placeholder, not a real signal.

### 4.2 Creation
- **Non-GK:** percentile of `assists per 90` within the §1.1 group.
- **GK:** fixed low value (20).

### 4.3 Carrying **[proxy — weakest attribute right now]**
- **Non-GK:** percentile of `(goals + assists) per 90` within the §1.1
  group. This is a general attacking-involvement signal, **not** a real
  ball-carrying/dribbling measure — that needs take-ons/progressive-carries
  data, which is gone (§0). Treat this attribute as the least trustworthy
  of the six until a replacement signal is found (Understat doesn't cover
  this either; would need a source with possession event data).
- **GK:** fixed low value (20).

### 4.4 Build-up **[proxy]**
- **Non-GK:** `Creation × 0.5 + Defense × 0.3 + Physical × 0.2` (computed
  from this doc's own §4.2/§4.5/§4.6 outputs). A composite stand-in for "is
  this player heavily involved in moving the ball through the team,"
  pending real passing/progression data (also gone, §0).
- **GK:** fixed value (30) — a shade above the other placeholder GK
  attributes since distribution starting from the back is at least
  plausibly correlated with save/build-up involvement, but this is not
  measured either.

### 4.5 Defense
- **Non-GK:** percentile of `(tackles_won + interceptions) per 90` within
  the §1.1 group, shrunk toward neutral for low-minute samples, then blended
  with the club's goals-against record (`TEAM_DEFENSE_WEIGHT`) for DF.
  - **This metric is INVERTED for CBs, not merely noisy.** Measured across
    all 10 loaded league-seasons, CBs with 1800+ minutes:

    | CB's team defense | mean tackles+interceptions /90 |
    |---|---|
    | Top 5 | **1.88** |
    | Middle | 2.03 |
    | Bottom 5 | **2.17** |

    Defenders at the *best* defenses post the *lowest* volume. Elite CBs
    don't need to tackle. An earlier version of this doc called it a "known
    weakness ... no fix available" and left it; that was too generous — a
    signal pointing the wrong way cannot be reweighted into correctness, and
    two attempts to do so failed before this was measured. **This is why
    `overall_rating` no longer comes from these attributes at all (§5).**
    Defense remains a published sub-attribute (it does describe defensive
    *workload*, which the sim can use), but it must not be treated as a
    proxy for defensive quality.
- **GK:** percentile of `save%` within all GKs. Also has a known bias: a
  keeper behind a dominant defense faces fewer, easier shots, which
  *deflates* raw save% relative to a keeper facing more difficult shots —
  David Raya (2023/24 Golden Glove winner, best clean-sheet record in the
  league) scored a middling Defense (45) for exactly this reason.

### 4.6 Physical
- **Non-GK:** percentile of raw `minutes played` within the §1.1 group —
  purely a reliability/durability signal (a manager trusts this player with
  minutes), not a real strength/speed/stamina measure. There is no
  aerial-duels-won, sprint-speed, or physical-contest data available at all
  (gone, §0) — this is the most honest fallback available, not a
  correlate-and-hope proxy like Carrying/Build-up.
- **GK:** percentile of `clean sheets per 90` within all GKs.

---

## 5. Overall rating — FIFA-anchored (REWRITTEN 2026-08-29)

`overall_rating` is **not** derived from the six attributes above. Quality
comes from an external anchor; the stats supply only a bounded
season-form modifier.

**Why the old approach was abandoned.** It computed a weighted blend of the
§4 attributes (`DF = Defense×0.5 + Physical×0.2 + Buildup×0.2 +
Carrying×0.1`, etc.). Because Defense is inverted for centre-backs (§4.5),
that formula systematically under-rated elite defenders — Van Dijk scored
55-65 across several attempts. Two redesigns were tried and both failed, and
are recorded in `etl/attributes.py` as dead code with docstrings so they
aren't retried:
1. **z-score normalization** instead of rank percentile — broke because
   goals/assists/tackles per90 are heavily right-skewed; it undervalued the
   elite tail (Lewandowski, the league's top scorer, fell to 74).
2. **midpoint-centred form delta** — handed every good player the full
   positive swing just for being good (Lewandowski 91 → 98).

**Current formula.** The anchor is the player's per-season FIFA/EA-FC
`overall` (edition N describes the player entering season N-1/N):

```
form_pct     = percentile of the player's §5-old stats score within their group
expected_pct = percentile of their FIFA base within the same group
confidence   = min(1, minutes / CONFIDENT_MINUTES)      # 1800
delta        = clamp((form_pct - expected_pct)/50 * swing * confidence, ±swing)
overall      = clamp(fifa_base + delta, 0, 99)
```

Both sides are percentiles over the same population deliberately, so the
delta measures **out/under-performance against the expectation the base
rating sets**, not "is this player good" (which the base already encodes).

`swing` is per §1.1 group, scaled by how much the surviving box-score stats
genuinely say about that role:

| Group | swing | rationale |
|---|---|---|
| FW | 8 | goals/assists per 90 really do measure a forward's season |
| MF | 5 | output is measurable; control/progression is not |
| GK | 4 | save% + clean sheets are real but heavily team-dependent |
| DF | 3 | the only defensive signal available is inverted (§4.5) |

**Fallback.** Players with no FIFA match keep the old stats-only weighted
rating and are tagged `rating_source="stats"`, so coverage is auditable
rather than silently wrong. Coverage is 95.3% of 5,723 player-seasons;
`etl/fifa_match_report.py` reports it per season and per match tier, and
`etl/fifa/unmatched_players.csv` lists the gaps.

**Sanity-checked:** Van Dijk 88-89, Haaland 88→91, Messi 94, Lewandowski 91,
De Bruyne 91, Son 87-89, Dunk 77-79, Araújo 82. Season medians for regulars
now sit at 77-79 in every one of the 10 league-seasons — previously 2023/24
sat at 59 while the rest sat at 77, which for a game whose core loop spins a
random season was a pool-breaking inconsistency, not a cosmetic one.

See `etl/attributes.py` ("bias fix, part 3"), `etl/fifa_ratings.py` for the
data source, and `etl/fifa_lookup.py` for the name-matching cascade.

---

## 6. Traits (rule-based, per §7.1/§7.2)

Only traits genuinely computable from available data are implemented.
Traits that need data we don't have (**Aerial Threat**, **Press-Resistant**,
**Pacy**) are deliberately **not implemented yet** — do not fabricate a
threshold against a weak/proxy stat just to fill out the original example
list; wait for real touches/duels/sprint data (§0) or drop them from the
game's trait vocabulary.

| Trait | Rule | Scope |
|---|---|---|
| Playmaker | Creation ≥ 80 | outfield |
| Clinical | Finishing ≥ 85 | FW group only |
| Iron Man | appearances ≥ 36 (out of 38) | all |
| Card Magnet | yellow + red cards ≥ 10 | all |
| Shot Stopper | save% ≥ 72 | GK only |

A player can hold multiple traits. Thresholds are round-number first
guesses, not tuned against a real distribution yet — §7 below flags this.

---

## 7. Open questions / next steps

1. **Should low-minutes players be excluded from the *comparison population***
   (not just given a percentile themselves)? A player with 90 minutes and 1
   goal has an inflated per-90 rate that currently pulls the whole curve.
   Recommend: exclude from population if minutes < 450, but still rank
   everyone (including the excluded) against that cleaned population.
2. **Trait thresholds are unvalidated** — need to check the actual
   percentile distribution once more seasons are loaded (e.g., what
   fraction of MFs currently clear Creation ≥ 80? If it's 40%, "Playmaker"
   isn't a meaningful trait).
3. **Overall-rating weights are a first guess** — revisit after loading
   enough seasons to compare against known real-world consensus quality
   rankings (Ballon d'Or shortlists, PFA Team of the Year, etc.) as a sanity
   check, not a ground truth to fit exactly.
4. **Understat.com as a supplementary xG source** — mentioned as an
   alternative during the pilot's data-source discussion but not yet
   evaluated for scraping feasibility. Would upgrade Finishing (shot
   quality vs. goals) and partially Carrying if it exposes any possession
   metrics. Worth a feasibility spike before committing to the full
   historical backfill.
5. **Pre-2010 seasons (§4's original Tier C)** — untested. FBref's coverage
   for very old seasons needs its own availability check before assuming
   the same field set works; expect goals/assists/apps/cards only, and
   confirm shots/tackles-won even exist that far back before relying on
   them.
6. **8-group (§1.2) role tagging currently only exists for the one pilot
   season** — extending to more seasons means repeating the Transfermarkt
   per-club-page pull (`etl/roles.py`'s approach) for every season/league
   added, tracking that a club's Transfermarkt ID may need re-deriving per
   season if promotion/relegation changes which 20 clubs are in the league.

---

## 8. Where this is implemented

- `etl/attributes.py` — the formula itself (percentiles, weights, traits)
- `etl/roles.py` — the 8-group role tagging (§1.2)
- `etl/build_dataset.py` — merges FBref tables + roles into one
  `PlayerSeason` record per player, which `attributes.py` consumes

Changing a weight or threshold means editing `attributes.py` and re-running
`etl/supabase_load.py` against a truncated `player_season_stats` table (see
`dream-xi-etl-pilot` memory for the truncate snippet) — attributes are
computed once at ETL time and stored, never recomputed on-device (§5.2).
