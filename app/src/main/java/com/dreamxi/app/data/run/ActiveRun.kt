package com.dreamxi.app.data.run

import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formation
import javax.inject.Inject
import javax.inject.Singleton

/** A finished draft, waiting to be played. */
data class CompletedDraft(
    val leagueId: Long,
    val leagueName: String,
    val formation: Formation,
    val picks: List<DraftedPlayer>,
    /**
     * Eight substitutes, auto-filled from squads the run passed through. They
     * change WHO SCORES, never how good the side is — see SquadProfile.
     */
    val bench: List<BenchPlayer> = emptyList(),
    /**
     * Fixed when the draft completes, so the season, the takeover draw and
     * every result stay the same across a rotation or a trip to the
     * background. Regenerating it per screen would reshuffle the league under
     * the user mid-season.
     */
    val seed: Long,
    /**
     * True when the XI was assembled in Free Mode rather than drafted.
     *
     * Carried so the season screens can say so. A 90-rated side winning a
     * league is a different achievement from a drafted one doing it, and the
     * two must never be mistaken for each other on screen.
     */
    val isFreeMode: Boolean = false,
)

/**
 * Hands a completed XI from the draft to the season.
 *
 * DELIBERATELY IN MEMORY ONLY, and this is the limitation to know about: a run
 * does not survive the app being killed, because there is no auth and no
 * persistence yet. When `draft_runs`/`simulation_results` are wired up this
 * becomes a cache in front of them rather than the source of truth, and the
 * screens should not need to change — they already treat it as somewhere a run
 * is fetched from rather than as a field they own.
 */
@Singleton
class ActiveRun @Inject constructor() {
    @Volatile
    var draft: CompletedDraft? = null
        private set

    fun start(draft: CompletedDraft) {
        this.draft = draft
    }

    fun clear() {
        draft = null
    }
}
