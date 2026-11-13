package com.dreamxi.app

import com.dreamxi.app.data.europeancup.EcEntryDto
import com.dreamxi.app.data.europeancup.EcMatchDto
import com.dreamxi.app.data.europeancup.EcSquadPlayerDto
import com.dreamxi.app.sim.europeancup.EcRealMatch
import com.dreamxi.app.sim.europeancup.EcRules
import com.dreamxi.app.sim.europeancup.EcStage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The European Cup's data classes, against rows copied from the live database
 * (src/test/resources/ec), so a column renamed in a migration fails here rather
 * than as a blank screen.
 */
class EuropeanCupDataTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun resource(name: String) =
        requireNotNull(javaClass.getResource("/ec/$name")) { "missing test resource $name" }.readText()

    @Test
    fun `away goals applied through 2020-21 and abolished from 2021-22`() {
        assertTrue(EcRules.awayGoalsApply(2009))
        assertTrue(EcRules.awayGoalsApply(2020))
        assertFalse("abolished from the 2021/22 season", EcRules.awayGoalsApply(2021))
        assertFalse(EcRules.awayGoalsApply(2023))
    }

    @Test
    fun `stage codes round trip and only the final and groups are one match`() {
        EcStage.entries.forEach { assertEquals(it, EcStage.fromCode(it.code)) }
        assertEquals(listOf(1, 2, 2, 2, 1), EcStage.entries.map { it.legs })
        assertFalse(EcStage.GROUP.isKnockout)
        assertTrue(EcStage.FINAL.isKnockout)
    }

    @Test
    fun `an entry row decodes with its club and squad embedded`() {
        val entries = json.decodeFromString<List<EcEntryDto>>(resource("entries.json"))
        val redStar = entries.single { it.club.name == "Red Star Belgrade" }
        // No roster, and a strength that says it was estimated rather than measured.
        assertFalse(redStar.squad.hasRoster)
        assertTrue(redStar.squad.estimated)
        assertTrue(redStar.finishedBottom)
        assertEquals(4, redStar.groupPosition)

        val madrid = entries.single { it.club.name == "Real Madrid" }
        assertTrue(madrid.squad.hasRoster)
        assertFalse(madrid.squad.estimated)
        assertEquals("winner", madrid.reachedStage)
    }

    @Test
    fun `a squad player carries his grid as deltas, and a keeper has none`() {
        val players = json.decodeFromString<List<EcSquadPlayerDto>>(resource("players.json"))
        val rodri = players.single { it.fullName == "Rodri" }
        assertNotNull(rodri.grid)
        val grid = rodri.grid!!
        assertEquals(0, grid.getValue("DM"))
        assertEquals("his full-back cost is -7, the figure the World Cup notes cite", -7, grid.getValue("FB"))
        assertEquals(7, grid.size)

        val ederson = players.single { it.fullName == "Ederson" }
        assertNull("a keeper has no positional grid", ederson.grid)
    }

    @Test
    fun `match rows decode, and a shootout carries both scores`() {
        val matches = json.decodeFromString<List<EcMatchDto>>(resource("matches.json"))
        assertTrue(matches.any { it.stage == "final" && !it.extraTime && it.homePens == null })
        val pens = matches.single { it.homePens != null }
        assertTrue("a shootout follows extra time", pens.extraTime)
        assertNotNull(pens.awayPens)

        val real = EcRealMatch(
            stage = EcStage.fromCode(pens.stage), group = pens.groupLabel, leg = pens.leg, date = pens.matchDate.orEmpty(),
            homeId = "e${pens.homeEntryId}", awayId = "e${pens.awayEntryId}", homeGoals = pens.homeGoals,
            awayGoals = pens.awayGoals, extraTime = pens.extraTime, homePens = pens.homePens, awayPens = pens.awayPens,
        )
        assertTrue(real.wentToPenalties)
        assertTrue(real.involves(real.homeId))
    }
}
