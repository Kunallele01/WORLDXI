"""
v0 attribute formula — PROJECT_SPEC_v2.md §7.

IMPORTANT: this is a first-draft formula built to validate the pilot
pipeline end-to-end (§13 Milestone 2), NOT the final "literal stat -> 0-99
mapping per position group per tier" reference doc that §7.3 calls for as
its own dedicated piece of work. Treat every weight/threshold here as
provisional and revisit once that spec exists and once richer per-season
data (xG, passing, aerials, take-ons) is available again.

Given what's actually free on FBref right now (§6.1 — the site's advanced
data provider pulled their feed in Jan 2026), this pilot only has:
  goals, assists, cards, minutes/appearances, shots/shots-on-target,
  tackles-won + interceptions, and full goalkeeper stats.
There is no possession/passing/aerial data, so Carrying and Build-up below
are weak proxies, clearly marked as such — not a real signal yet.

Normalization: percentile rank within (this season, this league, this
primary position group), among players with at least MIN_MINUTES of game
time, per §7.2's "normalize within-season, within-league, within-position-
group" principle. Below-threshold players still get a score (computed
against the same distribution) rather than being left blank, per §7.2's
graceful-degradation rule.

2026-08-29 bias fix, part 1 (DF/GK only — see ATTRIBUTE_FORMULA_SPEC.md
§4.5/§4.6 for the documented bias this addresses):
  - `tackles_won`-only defense (no interceptions/clearances/blocks — all
    gone, §0) structurally rewards busy/reactive defenders over composed
    ones (real example: Van Dijk 2023/24 scored a mid-pack Defense=53
    despite being elite). `save%`-only GK defense structurally *penalizes*
    keepers behind a dominant defense, who face fewer/easier shots (real
    example: David Raya's Golden Glove season scored Defense=45).
  - Neither is fixable by reweighting the same flawed personal stat alone.
    Fix: blend in **team defensive record** (goals-against per game from
    the league table, which we already load) as a second, unbiased-by-
    individual-busyness signal — a composed CB or a keeper behind a great
    defense now gets credit from the team's actual defensive output, not
    just their own counting stats. `TEAM_DEFENSE_WEIGHT` controls the blend.
  - A CB-specific overall weighting drops the (meaningless-for-that-role)
    goals+assists "carrying" proxy entirely rather than letting it drag a
    shutdown defender's score down with noise.

2026-08-29 bias fix, part 2 attempt (structural, all positions, TRIED AND
REVERTED): part 1 alone still under-rates genuinely elite players (real
example: Ronald Araújo scoring high-50s despite being a clearly
above-average starting CB). Hypothesis: empirical rank percentile can only
ever put ONE player at 99 per population, so several genuinely elite
performers in one season are forced to rank-compete for a single top slot
instead of all landing in the high-80s together. Tried switching every
metric-to-0-99 conversion to a z-score mapped through the normal CDF
(`_zscore_pct`, kept below as dead code with its own docstring explaining
the failure) — **this was wrong and reverted**: goals/assists/tackles per90
are heavily right-skewed (most players near zero, a thin elite tail), which
inflates the z-score population's stdev and badly undervalues that tail.
Verified regression before reverting: Lewandowski, La Liga 2022/23's actual
top scorer (23 goals), scored only Overall 74 — worse than several
rank-percentile results the change was meant to improve. Reverted to
`_percentile` (rank-based, distribution-free) everywhere. The Araújo-style
under-rating is real and still unsolved; see ATTRIBUTE_FORMULA_SPEC.md for
the next attempt, which should account for the skew (e.g. a log/sqrt
transform before any parametric normalization) rather than assume normality.

2026-08-29 bias fix, part 3 (the actual fix): parts 1 and 2 both tried to
squeeze a quality score out of box-score stats. Measured across all 10
loaded seasons, for CBs with 1800+ minutes, that is impossible in principle:

    CBs at the top-5 defenses    mean 1.88 tackles+interceptions /90
    CBs at mid-table defenses    mean 2.03
    CBs at the bottom-5 defenses mean 2.17

Defensive volume is INVERSELY correlated with defensive quality — elite
centre-backs make fewer defensive actions because they position well and
don't need to. No reweighting of an inverted signal can produce a correct
rating, which is why every attempt above failed.

So quality now comes from an external anchor (per-season FIFA/EA-FC overall,
edition N mapping to season N-1/N — see fifa_ratings.py), and the box-score
stats are demoted to what they can actually measure: how this player
performed in THIS season relative to their peers. The two combine as

    form_pct     = percentile of the player's stats score within their group
    expected_pct = percentile of their FIFA base within the same group
    confidence   = min(1, minutes / CONFIDENT_MINUTES)
    delta        = clamp((form_pct - expected_pct)/50 * swing * confidence)
    overall      = fifa_base + delta

Both sides are percentiles over the same population on purpose. Centring the
delta on the league midpoint instead (a first attempt) handed every good
player the full positive swing simply for being good, double-counting
quality and inflating the top — Lewandowski 91 -> 98. Comparing a raw
weighted score against a percentile (a second attempt) made everyone look
like an under-performer — Van Dijk 90 -> 83. Centring on the player's OWN
expected percentile means the modifier only moves a rating when the player
genuinely out- or under-performed the expectation their base rating sets.

This fixes both failure directions at once: FIFA's occasional stale or
over-generous ratings get corrected by real season data, while a hot
four-game run before a season-ending injury moves the rating by at most a
point or two instead of inventing a 90. Players with no FIFA match fall back
to the pure-stats rating and are tagged `rating_source="stats"` so coverage
stays auditable rather than silently wrong (fifa_match_report.py reports it).

Also fixed, and NOT reverted (orthogonal to the above): small-sample noise
wasn't just a DF/GK problem. A player who goes 3 goals + 2 assists in 4
games before a season-ending injury got zero confidence discount on
Finishing/Creation, over-crediting a tiny, possibly lucky sample as if it
were a full season's signal. `_shrink_to_neutral` (see below) is now
applied to every personal per90 metric — Finishing, Creation, Carrying, and
Defense/save% — not just the two DF/GK signals from part 1.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace

from build_dataset import PlayerSeason

MIN_MINUTES = 450  # ~5 full matches — below this, per90 rates are noisy but still used
CONFIDENT_MINUTES = 1800  # ~20 full matches — sample size we treat as fully reliable
TEAM_DEFENSE_WEIGHT = 0.45  # how much team GA-record counts vs. the personal defense stat

# --- FIFA quality anchor (see "bias fix, part 3" in the module docstring) ---
# How far a season's statistical performance may move a player off their FIFA
# base rating, at full minutes-confidence. Deliberately bounded: the stats are
# evidence about ONE season, not a re-derivation of player quality, and an
# unbounded delta would reintroduce exactly the hot-streak problem that
# _shrink_to_neutral exists to prevent.
#
# The bound is PER POSITION GROUP, scaled by how much our surviving box-score
# stats actually tell us about that role:
#   FW  goals/assists per 90 genuinely measure a forward's season      -> trust
#   MF  partly measurable (output), partly not (control, progression)  -> some
#   GK  save% + clean sheets are real but heavily team-dependent       -> less
#   DF  the only defensive signal we have is *inversely* correlated
#       with quality (see the docstring's top-5/bottom-5 table)        -> least
# Applying one uniform swing let the weakest signal move ratings as much as
# the strongest, which pulled good defenders down using the very metric that
# was proven backwards.
MAX_FORM_SWING_BY_GROUP = {"FW": 8, "MF": 5, "GK": 4, "DF": 3}


def _per90(total: int, minutes: int) -> float:
    return (total / minutes) * 90 if minutes > 0 else 0.0


def _zscore_pct(value: float, population: list[float]) -> int:
    """
    REVERTED 2026-08-29 (kept as dead code with this note, not deleted, so
    the mistake and reasoning stay visible): tried mapping value's z-score
    through the normal CDF instead of empirical rank, on the theory that
    rank percentile artificially crowds multiple elite performers down by
    forcing them to compete for a single top slot. This assumes the
    underlying metric is roughly normally distributed. Goals/assists/
    tackles per90 are NOT — they're heavily right-skewed (most players near
    zero, a long thin tail of elite performers), which inflates the
    population's standard deviation and badly *undervalues* that tail.
    Verified regression: Lewandowski, La Liga 2022/23's actual top scorer
    (23 goals), scored only Overall 74 under this method — worse than
    several rank-percentile results it was meant to improve. Reverted to
    `_percentile` (rank-based, distribution-free — doesn't assume
    normality) for all real call sites. A legitimate future fix along the
    original theory would need a skew-correcting transform (log/sqrt) on
    the population before z-scoring, not a plain z-score.
    """
    if not population:
        return 50
    n = len(population)
    mean = sum(population) / n
    variance = sum((v - mean) ** 2 for v in population) / n
    stdev = variance ** 0.5
    if stdev == 0:
        return 50
    z = (value - mean) / stdev
    cdf = 0.5 * (1 + math.erf(z / math.sqrt(2)))
    return max(0, min(99, round(cdf * 99)))


def _percentile(value: float, population: list[float]) -> int:
    """Returns value's percentile rank (0-99) within population, low-to-high."""
    if not population:
        return 50
    rank = sum(1 for v in population if v <= value)
    pct = rank / len(population)
    return max(0, min(99, round(pct * 99)))


def _shrink_to_neutral(pct: int, minutes: int) -> int:
    """
    Pulls a percentile toward the neutral midpoint (50) proportional to how
    far short of CONFIDENT_MINUTES the sample is, so a handful of matches
    can't swing a rating to the extreme top or bottom of the distribution.
    Full-confidence (minutes >= CONFIDENT_MINUTES) returns pct unchanged.
    """
    confidence = min(1.0, minutes / CONFIDENT_MINUTES)
    return round(50 + (pct - 50) * confidence)


def _team_defense_percentiles(team_ga_per_game: dict[str, float] | None) -> dict[str, int]:
    """
    Percentile of each club's goals-against-per-game within this league-
    season, inverted so a LOWER goals-against (a better defense) produces a
    HIGHER percentile. Used to offset the personal-stat bias documented in
    the module docstring — see ATTRIBUTE_FORMULA_SPEC.md §4.5/§4.6.
    """
    if not team_ga_per_game:
        return {}
    # Lower GA is better, so score on -GA.
    population = [-ga for ga in team_ga_per_game.values()]
    return {team: _percentile(-ga, population) for team, ga in team_ga_per_game.items()}


@dataclass
class PlayerAttributes:
    finishing: int
    creation: int
    carrying: int
    buildup: int
    defense: int
    physical: int
    overall_rating: int
    traits: list[str]
    # "fifa+form" when anchored to a FIFA base, "stats" when falling back to
    # the pure box-score rating (no FIFA match, or no dataset for the season)
    rating_source: str = "stats"


def _group_of(position: str) -> str:
    primary = position.split(",")[0]
    if primary == "GK":
        return "GK"
    if primary == "DF":
        return "DF"
    if primary == "MF":
        return "MF"
    return "FW"


def compute_all_attributes(
    seasons: list[PlayerSeason],
    team_ga_per_game: dict[str, float] | None = None,
    fifa_lookup=None,
    season_label: str | None = None,
) -> dict[tuple[str, str], PlayerAttributes]:
    """
    `team_ga_per_game` — {club_name: goals_against / matches_played} for this
    league-season's table, used only to offset the DF/GK personal-stat bias
    documented in the module docstring. Optional so existing callers/tests
    that don't pass it still get a score (team-defense blend degrades to
    neutral 50 for every team rather than erroring).

    `fifa_lookup` / `season_label` — a fifa_lookup.FifaLookup and this
    season's label ("2021/22"). When both are supplied, overall_rating
    becomes the FIFA quality base adjusted by bounded season form (see
    "bias fix, part 3"). When either is missing, or the player has no FIFA
    match, the pure-stats rating is used unchanged and `rating_source`
    records which path was taken so coverage stays auditable.
    """
    team_defense_pct = _team_defense_percentiles(team_ga_per_game)

    groups: dict[str, list[PlayerSeason]] = {"GK": [], "DF": [], "MF": [], "FW": []}
    for s in seasons:
        groups[_group_of(s.position)].append(s)

    # Pre-compute per-group metric populations for percentile ranking.
    pop_goals90 = {g: [_per90(s.goals, s.minutes) for s in ps] for g, ps in groups.items()}
    pop_assists90 = {g: [_per90(s.assists, s.minutes) for s in ps] for g, ps in groups.items()}
    pop_defense90 = {
        g: [_per90(s.tackles_won + s.interceptions, s.minutes) for s in ps] for g, ps in groups.items()
    }
    pop_ga90 = {g: [_per90(s.goals + s.assists, s.minutes) for s in ps] for g, ps in groups.items()}
    pop_minutes = {g: [float(s.minutes) for s in ps] for g, ps in groups.items()}
    pop_save_pct = [s.gk_save_pct or 0.0 for s in groups["GK"]]
    pop_clean_sheets90 = [
        _per90(s.gk_clean_sheets or 0, s.minutes) for s in groups["GK"]
    ]

    # Resolve every FIFA base up-front so the per-group population of bases is
    # known before any player is scored (needed for the expected-form centring
    # below, which compares a player against others at their own FIFA level).
    fifa_base_by_identity: dict[tuple[str, str], int] = {}
    if fifa_lookup is not None and season_label:
        for s in seasons:
            fb = fifa_lookup.get(s.player, s.birth_year, season_label, s.team)
            if fb is not None:
                fifa_base_by_identity[(s.player, s.team)] = fb
    pop_fifa_base: dict[str, list[float]] = {g: [] for g in groups}
    for s in seasons:
        fb = fifa_base_by_identity.get((s.player, s.team))
        if fb is not None:
            pop_fifa_base[_group_of(s.position)].append(float(fb))

    results: dict[tuple[str, str], PlayerAttributes] = {}
    for s in seasons:
        group = _group_of(s.position)
        identity = (s.player, s.team)

        team_pct = team_defense_pct.get(s.team, 50)

        if group == "GK":
            save_pct = _shrink_to_neutral(_percentile(s.gk_save_pct or 0.0, pop_save_pct), s.minutes)
            defense = round(save_pct * (1 - TEAM_DEFENSE_WEIGHT) + team_pct * TEAM_DEFENSE_WEIGHT)
            cs90 = _per90(s.gk_clean_sheets or 0, s.minutes)
            physical = _percentile(cs90, pop_clean_sheets90)
            finishing, creation, carrying, buildup = 20, 20, 20, 30
            overall = round(defense * 0.7 + physical * 0.3)
            traits = []
            if (s.gk_save_pct or 0) >= 72:
                traits.append("Shot Stopper")
        else:
            # Small-sample shrinkage now applies to every personal per90 metric
            # (see module docstring, "bias fix, part 2") — a hot 4-game stretch
            # before a season-ending injury shouldn't score like a full season.
            finishing = _shrink_to_neutral(
                _percentile(_per90(s.goals, s.minutes), pop_goals90[group]), s.minutes
            )
            creation = _shrink_to_neutral(
                _percentile(_per90(s.assists, s.minutes), pop_assists90[group]), s.minutes
            )
            tackling = _shrink_to_neutral(
                _percentile(_per90(s.tackles_won + s.interceptions, s.minutes), pop_defense90[group]),
                s.minutes,
            )
            if group == "DF":
                # Scoped fix (see module docstring, "bias fix, part 1"): blend
                # the shrunk personal signal with team defensive record.
                defense = round(tackling * (1 - TEAM_DEFENSE_WEIGHT) + team_pct * TEAM_DEFENSE_WEIGHT)
            else:
                defense = tackling
            # Weak proxies pending real possession/passing data (see module docstring):
            carrying = _shrink_to_neutral(
                _percentile(_per90(s.goals + s.assists, s.minutes), pop_ga90[group]), s.minutes
            )
            physical = _percentile(float(s.minutes), pop_minutes[group])
            buildup = round(creation * 0.5 + defense * 0.3 + physical * 0.2)

            if group == "DF":
                if s.role_group == "CB":
                    # goals+assists is noise for a shutdown CB (see module docstring:
                    # carrying is a weak proxy everywhere, but actively misleading
                    # here) — drop it and lean harder on defense/physical, the two
                    # signals that actually mean something for this role.
                    overall = round(defense * 0.55 + physical * 0.25 + buildup * 0.20)
                else:  # FB — attacking output from overlaps is a real (if weak) signal
                    overall = round(defense * 0.5 + physical * 0.2 + buildup * 0.2 + carrying * 0.1)
            elif group == "MF":
                overall = round(
                    creation * 0.3 + buildup * 0.25 + defense * 0.2 + carrying * 0.15 + finishing * 0.1
                )
            else:  # FW
                overall = round(finishing * 0.45 + creation * 0.2 + carrying * 0.2 + physical * 0.15)

            traits = []
            if creation >= 80:
                traits.append("Playmaker")
            if group == "FW" and finishing >= 85:
                traits.append("Clinical")

        if s.appearances >= 36:
            traits.append("Iron Man")
        if (s.yellow_cards + s.red_cards) >= 10:
            traits.append("Card Magnet")

        # --- FIFA quality anchor + bounded season-form modifier ---
        # `overall` above is season FORM (how this player performed, from box
        # scores). It is NOT a quality measure — see the module docstring for
        # the proof that defensive box scores invert against real quality.
        results[identity] = PlayerAttributes(
            finishing=finishing,
            creation=creation,
            carrying=carrying,
            buildup=buildup,
            defense=defense,
            physical=physical,
            overall_rating=max(0, min(99, overall)),
            traits=traits,
            rating_source="stats",
        )

    # ---- second pass: FIFA quality anchor + bounded season-form modifier ----
    # Two passes are required because the modifier compares a player's season
    # form against the form EXPECTED at their FIFA level, and both sides have
    # to be percentiles over the whole group population — which isn't known
    # until every player's stats-based score exists.
    if not fifa_base_by_identity:
        return results

    pop_form: dict[str, list[float]] = {g: [] for g in groups}
    for s in seasons:
        pop_form[_group_of(s.position)].append(float(results[(s.player, s.team)].overall_rating))

    for s in seasons:
        identity = (s.player, s.team)
        fifa_base = fifa_base_by_identity.get(identity)
        if fifa_base is None:
            continue  # keep the pure-stats rating, tagged rating_source="stats"
        group = _group_of(s.position)
        attrs = results[identity]

        # Percentile-vs-percentile so the two sides are on the same scale:
        # comparing a raw weighted score against a percentile (an earlier
        # attempt) made every player look like an under-performer.
        form_pct = _percentile(float(attrs.overall_rating), pop_form[group])
        expected_pct = _percentile(float(fifa_base), pop_fifa_base[group])
        swing = MAX_FORM_SWING_BY_GROUP[group]
        confidence = min(1.0, s.minutes / CONFIDENT_MINUTES)
        raw = (form_pct - expected_pct) / 50 * swing * confidence
        delta = max(-swing, min(swing, raw))

        results[identity] = replace(
            attrs,
            overall_rating=max(0, min(99, round(fifa_base + delta))),
            rating_source="fifa+form",
        )
    return results


if __name__ == "__main__":
    from build_dataset import build_player_seasons
    from parse_raw import parse_league_table

    seasons = build_player_seasons()
    league_table = parse_league_table("league_table_2023-2024_PL.txt")
    team_ga_per_game = {row.club: row.goals_against / row.played for row in league_table if row.played}
    attrs = compute_all_attributes(seasons, team_ga_per_game=team_ga_per_game)

    for name in ["Erling Haaland", "Rodri", "Virgil van Dijk", "David Raya", "Bukayo Saka"]:
        season = next((s for s in seasons if s.player == name), None)
        if not season:
            continue
        a = attrs[(season.player, season.team)]
        print(f"{name:20s} ({season.position:6s}) OVR {a.overall_rating:2d}  "
              f"FIN {a.finishing:2d} CRE {a.creation:2d} CAR {a.carrying:2d} "
              f"BLD {a.buildup:2d} DEF {a.defense:2d} PHY {a.physical:2d}  {a.traits}")
