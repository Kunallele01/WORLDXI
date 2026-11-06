package com.dreamxi.app

import com.dreamxi.app.data.run.ActiveRun
import com.dreamxi.app.data.run.CompletedDraft
import com.dreamxi.app.data.run.ResumeOffer
import com.dreamxi.app.data.run.RunSnapshotStore
import com.dreamxi.app.data.run.SavedDraft
import com.dreamxi.app.data.run.SavedSeason
import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.VisitedSquad
import com.dreamxi.app.ui.theme.PlayerPosition
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A run in progress has to come back exactly as it was left.
 *
 * "Exactly" is the whole requirement: a season is decided by the XI and its seed,
 * so a snapshot that dropped a player's position grid, or reordered the XI, would
 * resume into a DIFFERENT season without anyone noticing.
 */
class RunSnapshotStoreTest {

    private lateinit var dir: File
    private lateinit var store: RunSnapshotStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("era-xi-store").toFile()
        store = RunSnapshotStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private var nextId = 1L

    private fun player(name: String, role: String, ovr: Int) = SquadPlayer(
        playerSeasonStatId = nextId++,
        playerId = nextId * 100,
        fullName = name,
        role = role,
        group = if (role == "GK") PlayerPosition.GOALKEEPER else PlayerPosition.MIDFIELDER,
        overallRating = ovr,
        minutes = 2400,
        goals = 7,
        assists = 3,
        savePct = if (role == "GK") 71.5 else null,
        nationality = "Spain",
        side = "L",
        // The part most likely to be lost: a per-position grid.
        positionRatings = mapOf("cm" to ovr, "dm" to ovr - 4, "cam" to ovr - 2),
        eaPositions = listOf("CM", "CAM"),
    )

    private fun draft(): CompletedDraft {
        val formation = Formations.first { it.id == "4-3-3" }
        val picks = formation.slots.map { slot ->
            val role = if (slot.isGoalkeeper) "GK" else "CM"
            DraftedPlayer(slot, player("Player ${slot.id}", role, 80 + slot.id.length), "Arsenal", "2003/04")
        }
        val bench = (1..8).map { BenchPlayer(player("Sub $it", "CM", 70), "CM", "Chelsea", "2009/10") }
        return CompletedDraft(
            leagueId = 5L, leagueName = "La Liga", formation = formation,
            picks = picks, bench = bench, seed = 1_762_000_000_123L, isFreeMode = true,
        )
    }

    @Test
    fun a_season_comes_back_identical() {
        val original = SavedSeason(draft(), matchday = 17)
        store.saveSeason(original)
        // A fresh store over the same directory is what a restarted app sees.
        assertEquals(original, RunSnapshotStore(dir).loadSeason())
    }

    @Test
    fun the_xi_keeps_its_order_and_its_slots() {
        val original = draft()
        store.saveSeason(SavedSeason(original))
        val back = store.loadSeason()!!.draft
        assertEquals(original.picks.map { it.slot.id }, back.picks.map { it.slot.id })
        assertEquals(original.picks.map { it.effectiveRating }, back.picks.map { it.effectiveRating })
        assertEquals(original.seed, back.seed)
        assertTrue(back.isFreeMode)
    }

    @Test
    fun a_draft_in_progress_keeps_the_squad_that_was_on_screen() {
        val formation = Formations.first { it.id == "4-3-3" }
        val onScreen = SpinResult(
            clubSeasonId = 42, clubName = "Valencia", seasonLabel = "2009/10",
            finalPosition = 3, squadStrength = 79.5, strengthQuartile = 3,
            players = listOf(player("A", "GK", 80), player("B", "CM", 78)),
        )
        val saved = SavedDraft(
            leagueId = 5, leagueName = "La Liga", formation = formation,
            rerollsAllowed = 2, rerollsUsed = 1,
            picks = listOf(DraftedPlayer(formation.slots.first(), player("GK", "GK", 85), "Arsenal", "2003/04")),
            spin = onScreen,
            visited = listOf(VisitedSquad("Arsenal", "2003/04", listOf(player("X", "CM", 70)))),
        )
        store.saveDraft(saved)
        val back = RunSnapshotStore(dir).loadDraft()!!
        assertEquals(saved, back)
        // The squad was dealt, not re-rolled: closing the app cannot buy a better one.
        assertEquals(onScreen, back.spin)
        assertEquals(1, back.rerollsUsed)
    }

    @Test
    fun an_unreadable_file_is_discarded_not_fatal() {
        File(dir, "saved_season.json").writeText("{ this is not json")
        assertNull(store.loadSeason())
        // And it is gone, so the next launch does not trip over it again.
        assertFalse(File(dir, "saved_season.json").exists())
    }

    @Test
    fun a_snapshot_from_a_newer_app_with_extra_fields_still_loads() {
        store.saveSeason(SavedSeason(draft(), 3))
        val file = File(dir, "saved_season.json")
        file.writeText(file.readText().replaceFirst("{", "{\"someFutureField\":1,"))
        assertNotNull(store.loadSeason())
    }

    @Test
    fun the_offer_describes_the_run_and_prefers_a_season_over_a_draft() {
        assertNull(store.offer())
        val formation = Formations.first { it.id == "4-3-3" }
        store.saveDraft(
            SavedDraft(5, "La Liga", formation, 2, 0,
                picks = listOf(DraftedPlayer(formation.slots.first(), player("GK", "GK", 85), "Arsenal", "2003/04"))),
        )
        assertEquals(ResumeOffer.Kind.DRAFT, store.offer()!!.kind)
        assertEquals("1 of 11 picked", store.offer()!!.progress)

        store.saveSeason(SavedSeason(draft(), matchday = 12))
        val offer = store.offer()!!
        assertEquals(ResumeOffer.Kind.SEASON, offer.kind)
        assertEquals("Matchday 12 played", offer.progress)
        assertEquals("La Liga", offer.leagueName)
    }

    @Test
    fun starting_a_season_replaces_the_draft_and_progress_is_remembered() {
        val run = ActiveRun(store)
        val formation = Formations.first { it.id == "4-3-3" }
        store.saveDraft(SavedDraft(5, "La Liga", formation, 2, 0, picks = emptyList(), visited = emptyList()))

        run.start(draft())
        assertNull("the finished draft's snapshot is cleared", store.loadDraft())
        assertEquals(0, store.loadSeason()!!.matchday)

        run.progress(9)
        assertEquals(9, store.loadSeason()!!.matchday)

        // A new process: nothing in memory, only the file.
        val restarted = ActiveRun(RunSnapshotStore(dir))
        assertTrue(restarted.restore())
        assertEquals(9, restarted.resumeMatchday)
        assertEquals(draft().seed, restarted.draft!!.seed)
    }

    @Test
    fun a_finished_season_cannot_be_resurrected_by_a_late_progress_write() {
        val run = ActiveRun(store)
        run.start(draft())
        run.finish()
        run.progress(38) // the last matchday's write, arriving after the clean-up
        assertNull(store.loadSeason())
    }
}
