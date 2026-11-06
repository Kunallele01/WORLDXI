package com.dreamxi.app.data.run

import android.content.Context
import android.util.Log
import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.feature.draft.VisitedSquad
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DreamXI"

/** A season that has been set up and is being played: the XI, and how far through it is. */
@Serializable
data class SavedSeason(
    val draft: CompletedDraft,
    /** Matchdays the player has seen. 0 means the takeover draw has not been played past. */
    val matchday: Int = 0,
)

/** A draft part-way through: everything needed to put the screen back as it was. */
@Serializable
data class SavedDraft(
    val leagueId: Long,
    val leagueName: String,
    val formation: Formation,
    val rerollsAllowed: Int,
    val rerollsUsed: Int,
    val picks: List<DraftedPlayer>,
    /**
     * The squad on screen when the app was left. Saved rather than re-rolled on
     * return, so closing the app can never be used to draw a better squad.
     */
    val spin: SpinResult? = null,
    /** Every squad the run has seen, from which the bench is filled at the end. */
    val visited: List<VisitedSquad> = emptyList(),
    val bench: List<BenchPlayer> = emptyList(),
)

/** What the Setup screen shows as the way back in. */
data class ResumeOffer(
    val kind: Kind,
    val leagueName: String,
    val formationName: String,
    val progress: String,
) {
    enum class Kind { DRAFT, SEASON }
}

/**
 * The run in progress, kept on the device so it survives the app being closed.
 *
 * LOCAL, NOT THE DATABASE. A run in progress is private scratch state with a
 * short life; it must work offline and for a guest, and needs no schema change.
 * Finished runs are still saved to the account (see [RunHistoryRepository]).
 * Putting the in-progress state there too would mean a network write after every
 * pick for the sake of a case — switching phones mid-draft — nobody has asked for.
 *
 * At most one of each kind exists. Starting a new run replaces the old one.
 *
 * A snapshot that cannot be read — the data classes changed in an update, or the
 * file is corrupt — is DISCARDED, not repaired: losing a half-played run is a
 * small cost, crashing on launch because of one is not.
 */
@Singleton
class RunSnapshotStore internal constructor(private val dir: File) {

    /** The app's own constructor; the one above takes a plain directory so tests can use a temp one. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val seasonFile get() = File(dir, "saved_season.json")
    private val draftFile get() = File(dir, "saved_draft.json")

    @Synchronized
    fun saveSeason(season: SavedSeason) = write(seasonFile) { json.encodeToString(SavedSeason.serializer(), season) }

    @Synchronized
    fun loadSeason(): SavedSeason? = read(seasonFile) { json.decodeFromString(SavedSeason.serializer(), it) }

    @Synchronized
    fun clearSeason() { seasonFile.delete() }

    @Synchronized
    fun saveDraft(draft: SavedDraft) = write(draftFile) { json.encodeToString(SavedDraft.serializer(), draft) }

    @Synchronized
    fun loadDraft(): SavedDraft? = read(draftFile) { json.decodeFromString(SavedDraft.serializer(), it) }

    @Synchronized
    fun clearDraft() { draftFile.delete() }

    /**
     * What there is to continue, if anything. A season in progress outranks a
     * draft: if both exist the draft is the older, abandoned one.
     */
    fun offer(): ResumeOffer? {
        loadSeason()?.let { s ->
            val d = s.draft
            val progress = if (s.matchday <= 0) "Ready to kick off" else "Matchday ${s.matchday} played"
            return ResumeOffer(ResumeOffer.Kind.SEASON, d.leagueName, d.formation.name, progress)
        }
        loadDraft()?.let { d ->
            val total = d.formation.slots.size
            return ResumeOffer(
                ResumeOffer.Kind.DRAFT, d.leagueName, d.formation.name,
                "${d.picks.size} of $total picked",
            )
        }
        return null
    }

    private fun write(file: File, encode: () -> String) {
        runCatching {
            // Written beside the target then renamed, so a process killed
            // mid-write leaves the previous snapshot intact instead of half a file.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { warn("could not save ${file.name}", it) }
    }

    /** Logging must never be the thing that fails (it is not available in plain JVM tests). */
    private fun warn(message: String, t: Throwable) {
        runCatching { Log.w(TAG, message, t) }
    }

    private fun <T> read(file: File, decode: (String) -> T): T? {
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }
            .onFailure {
                warn("discarding unreadable ${file.name}", it)
                file.delete()
            }
            .getOrNull()
    }
}
