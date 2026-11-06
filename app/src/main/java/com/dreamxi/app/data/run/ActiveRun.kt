package com.dreamxi.app.data.run

import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formation
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A finished draft, waiting to be played. */
@Serializable
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
 * Held in memory and WRITTEN THROUGH to [RunSnapshotStore], so a run survives
 * the app being closed. The screens still treat this as somewhere a run is
 * fetched from rather than a field they own, which is why none of them had to
 * change to gain resume.
 *
 * A season is fully determined by the XI and its seed, so saving the XI plus how
 * many matchdays have been seen is enough to put it back exactly.
 */
@Singleton
class ActiveRun @Inject constructor(
    private val store: RunSnapshotStore,
) {
    @Volatile
    var draft: CompletedDraft? = null
        private set

    /** Matchdays already seen when this run was resumed; 0 for a fresh one. */
    @Volatile
    var resumeMatchday: Int = 0
        private set

    @Synchronized
    fun start(draft: CompletedDraft) {
        this.draft = draft
        this.resumeMatchday = 0
        this.finished = false
        // The draft is finished: its snapshot has done its job.
        store.clearDraft()
        store.saveSeason(SavedSeason(draft, matchday = 0))
    }

    /** Puts a saved season back as the active run. Returns false if there is none. */
    @Synchronized
    fun restore(): Boolean {
        val saved = store.loadSeason() ?: return false
        draft = saved.draft
        resumeMatchday = saved.matchday
        finished = false
        return true
    }

    /** Records how far through the season the player has got. */
    @Synchronized
    fun progress(matchday: Int) {
        val current = draft ?: return
        // A progress write queued behind the finish must not bring the file back.
        if (finished) return
        // Also kept in memory: a season screen rebuilt while the app is alive
        // (rotation, coming back from the profile) must pick up where it was,
        // not from the matchday this run was first resumed at.
        resumeMatchday = matchday
        store.saveSeason(SavedSeason(current, matchday))
    }

    /**
     * The season is over and its result is in the history, so there is nothing
     * left to resume. The run stays in memory for the screens still showing it.
     *
     * Synchronized with [progress] and flagged, because the last matchday's
     * progress write and this clean-up are issued back to back from different
     * threads, and without the flag the write could land second and recreate
     * the file this deletes.
     */
    @Synchronized
    fun finish() {
        finished = true
        store.clearSeason()
    }

    @Volatile
    private var finished = false

    fun clear() {
        draft = null
        resumeMatchday = 0
        store.clearSeason()
    }
}
