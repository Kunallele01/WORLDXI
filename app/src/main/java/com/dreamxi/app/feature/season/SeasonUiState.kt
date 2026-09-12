package com.dreamxi.app.feature.season

import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.sim.SimFixture
import com.dreamxi.app.sim.FixtureOutlook
import com.dreamxi.app.sim.FixtureOutlooks
import com.dreamxi.app.sim.SeasonAnalysis
import com.dreamxi.app.sim.SeasonProjection
import com.dreamxi.app.sim.SeasonStats
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.TableRow
import com.dreamxi.app.sim.TakeoverResult

/** Where a run is in the journey from a finished XI to a finished season. */
enum class SeasonPhase {
    /** Fetching the season and rating every club in it. */
    Loading,

    /** The draw between the three relegated clubs, playing out. */
    Drawing,

    /** The draw has settled. The user sees whose place they take. */
    PlaceWon,

    /** The season, one matchday at a time. */
    Playing,

    /** All 38 played. */
    Finished,
}

/**
 * Everything the season screens render.
 *
 * The whole season is simulated up front and revealed a matchday at a time.
 * That is not laziness — it is the only honest way to do it. A league table is
 * a single joint outcome, so a season generated week by week as the user taps
 * would let the seed drift with their pace and would make "replay this run"
 * impossible. [visibleMatchday] is a curtain over a result that already exists.
 */
data class SeasonUiState(
    val phase: SeasonPhase = SeasonPhase.Loading,
    val leagueName: String = "",
    /** True when the XI was built in Free Mode rather than drafted. */
    val isFreeMode: Boolean = false,
    val seasonLabel: String = "",
    val userTeam: SimTeam? = null,
    /** The three real relegated clubs, in the order the draw shows them. */
    val drawCandidates: List<SimTeam> = emptyList(),
    val takeover: TakeoverResult? = null,
    /** How far through the draw the animation has played. */
    val drawTick: Int = 0,
    val replacedClub: SimTeam? = null,
    /** Where the replaced club really finished, e.g. 19. */
    val replacedClubRealPosition: Int? = null,
    val fixtures: List<SimFixture> = emptyList(),
    /**
     * The table as it stands after [visibleMatchday], recomputed by the
     * ViewModel each week from the fixtures played so far. A league position
     * only means anything relative to the games behind it, so this is not a
     * slice of the final table.
     */
    val table: List<TableRow> = emptyList(),
    val totalMatchdays: Int = 0,
    /** Matchdays played so far; 0 before kick-off. */
    val visibleMatchday: Int = 0,
    /**
     * End-of-season leaderboards. Built once the last matchday is played —
     * showing them mid-season would spoil nothing, but a "top scorer" after
     * three games is noise dressed up as a fact.
     */
    val stats: SeasonStats? = null,
    /**
     * Expected points and goals against what actually happened, plus the
     * home/away split. Built with [stats] once the last matchday is played.
     */
    val analysis: SeasonAnalysis? = null,
    /**
     * Cut points splitting the user's own 38 fixtures into difficulty bands.
     * Computed once when the season is built; see FixtureOutlooks.
     */
    val difficultyThresholds: List<Double> = emptyList(),
    /**
     * What this XI is worth over many seasons, as opposed to what happened to
     * it once. Computed in the background while the takeover draw plays, so it
     * is ready by the time the user sees the reveal.
     */
    val projection: SeasonProjection? = null,
    /** True while the rest of the season is playing itself out. */
    val isAdvancing: Boolean = false,
    /** True while the separate statistics screen is up. */
    val isViewingStats: Boolean = false,
    /** True while the "leave without seeing the statistics?" dialog is up. */
    val isConfirmingExit: Boolean = false,
    /**
     * Whether the statistics screen has been opened at least once this run.
     *
     * The exit prompt exists to stop someone discarding a season's statistics
     * without knowing they were there. Once they have been looked at it has
     * nothing left to warn about, and asking again is just a second tap
     * between the user and the door.
     */
    val hasSeenStats: Boolean = false,
    val error: DreamXiError? = null,
) {
    /** Tallies at the current point of the draw, for the bars on screen. */
    val drawTallies: Map<String, Int>
        get() = takeover?.ticks?.getOrNull(drawTick - 1)?.tallies
            ?: drawCandidates.associate { it.id to 0 }

    val drawComplete: Boolean
        get() = takeover != null && drawTick >= takeover.ticks.size

    /** True once the draw has entered extra draws between tied leaders. */
    val inSuddenDeath: Boolean
        get() = takeover?.suddenDeathFrom?.let { drawTick > it } == true

    /** The matches of the most recently played week. */
    val currentResults: List<SimFixture>
        get() = fixtures.filter { it.matchday == visibleMatchday }

    /** The user's match this week, which the screen leads with. */
    val userResult: SimFixture?
        get() = currentResults.firstOrNull { it.home.isUserTeam || it.away.isUserTeam }

    /**
     * The fixtures ahead, with opponent form and difficulty. Empty once the
     * season is over.
     */
    val upcoming: List<FixtureOutlook>
        get() {
            val team = userTeam ?: return emptyList()
            if (fixtures.isEmpty() || isSeasonOver) return emptyList()
            return FixtureOutlooks.next(
                fixtures = fixtures,
                team = team,
                table = table,
                afterMatchday = visibleMatchday,
                thresholds = difficultyThresholds,
            )
        }

    val userRow: TableRow?
        get() = table.firstOrNull { it.team.isUserTeam }

    val isSeasonOver: Boolean
        get() = totalMatchdays > 0 && visibleMatchday >= totalMatchdays
}
