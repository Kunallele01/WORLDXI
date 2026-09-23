package com.dreamxi.app

import com.dreamxi.app.data.worldcup.WcDraftPlayer
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.ratingAt
import com.dreamxi.app.feature.worldcup.WcStages
import com.dreamxi.app.feature.worldcup.toSquadPlayer
import com.dreamxi.app.sim.worldcup.FINAL_ROUND
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.THIRD_PLACE_ROUND
import com.dreamxi.app.sim.worldcup.WcTournamentSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WcScreensTest {

    /**
     * The draft pitch prices a World Cup player out of position through the
     * club draft's own grid code, fed a grid built from the measured cost table.
     * The engine prices him through NationalSide directly. If those two ever
     * disagree, the rating the user sees on the pitch is not the one the
     * tournament plays — so every player in every 2026 squad is checked in
     * every slot of every formation.
     */
    @Test
    fun `the rating the draft shows is the rating the engine plays`() {
        var checked = 0
        for ((key, squad) in WcFixtures.squads) {
            if (key.first != 2026) continue
            for (player in squad) {
                val squadPlayer = WcDraftPlayer(1L, player.id, null, player).toSquadPlayer(emptySet())
                for (formation in Formations) {
                    for (slot in formation.slots) {
                        val engineCost = NationalSide.positionCost(player, slot.role, slot.side, slot.isWingBack) ?: continue
                        val engine = (player.overall + engineCost).coerceIn(1, 99)
                        assertEquals("${player.name} at ${slot.label} in ${formation.id}", engine, squadPlayer.ratingAt(slot))
                        checked++
                    }
                }
            }
        }
        println("draft/engine rating parity: $checked player-slot pairs")
        assertTrue(checked > 10_000)
    }

    /**
     * World Cup magic: the club solver over every player at every World Cup.
     * The eleven must be eleven different PEOPLE (Messi at 2014 and 2022 is one
     * man), keepers only in goal, and it should land where the dream-XI
     * measurement did — while a second press is still a different side.
     */
    @Test
    fun `world cup magic builds a real eleven from every squad`() {
        val candidates = WcFixtures.tournaments.flatMap { (year, data) ->
            data.entries.flatMap { entry ->
                WcFixtures.squads[year to entry.id].orEmpty().map { p ->
                    com.dreamxi.app.feature.freemode.MagicCandidate(
                        player = WcDraftPlayer(1L, p.id, null, p).toSquadPlayer(emptySet()),
                        clubSeasonId = 0L,
                        clubName = entry.name,
                        seasonLabel = year.toString(),
                    )
                }
            }
        }
        val formation = Formations.first { it.id == "4-3-3" }
        val sides = (0 until 20).map { seed ->
            val xi = com.dreamxi.app.feature.freemode.MagicXi.build(candidates, formation, kotlin.random.Random(seed))
            assertEquals(11, xi.size)
            assertEquals("a person picked twice", 11, xi.map { it.candidate.player.playerId }.toSet().size)
            assertTrue(xi.all { (it.candidate.player.role == "GK") == it.slot.isGoalkeeper })
            if (seed == 0) {
                println("world cup magic 4-3-3: " + xi.joinToString { "${it.candidate.player.fullName} (${it.candidate.clubName} ${it.candidate.seasonLabel}) ${it.slot.label} ${it.effectiveRating}" })
            }
            xi
        }
        val ratings = sides.map { xi -> xi.sumOf { it.effectiveRating } }
        // Variety is WHO plays, not the total: near-equal players trade places and
        // the sum can come out identical every time.
        val distinct = sides.map { xi -> xi.associate { it.slot.id to it.candidate.player.playerId } }.toSet().size
        println("magic XI over 20 presses: $distinct different sides, total rating ${ratings.min()}-${ratings.max()}")
        assertTrue("jitter should vary the side", distinct > 1)
        assertTrue("jitter must stay within a point a shirt of the optimum", ratings.max() - ratings.min() <= 11)
        // The case that prompted per-player grids: a defensive midfielder is not
        // the best left-back in World Cup history.
        assertTrue("Rodri was put at full-back again", sides.none { xi -> xi.any { it.candidate.player.fullName == "Rodri" && it.slot.role == "FB" } })
    }

    /** Every nation a World Cup shows has a flag; a new spelling would otherwise fall back to initials. */
    @Test
    fun `every nation has a flag`() {
        val missing = WcFixtures.years
            .flatMap { WcFixtures.tournaments.getValue(it).entries.map { e -> e.name } }
            .toSet()
            .filterNot { it in com.dreamxi.app.core.ui.NationFlags }
        assertEquals("nations without a flag", emptyList<String>(), missing)
    }

    @Test
    fun `a tournament splits into group matchdays and knockout rounds`() {
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            val result = WcTournamentSimulator.play(data, null, null, seed = 3L)
            val (stages, stageOf) = WcStages.build(result.matches)
            val groups = data.groups.size
            val expectedStages = if (year == 2026) 8 else 7
            assertEquals("$year stage count", expectedStages, stages.size)

            for (md in 0..2) {
                val inStage = result.matches.filterIndexed { i, _ -> stageOf[i] == md }
                assertEquals("$year matchday ${md + 1}", groups * 2, inStage.size)
                assertTrue(inStage.all { it.round == GROUP_ROUND })
                // Two per group, and never the same nation twice in a matchday.
                assertTrue(inStage.groupBy { it.group }.values.all { it.size == 2 })
                val teams = inStage.flatMap { listOf(it.homeId, it.awayId) }
                assertEquals("$year matchday ${md + 1} repeats a nation", teams.size, teams.toSet().size)
            }
            val last = result.matches.filterIndexed { i, _ -> stageOf[i] == stages.lastIndex }
            assertEquals(setOf(FINAL_ROUND, THIRD_PLACE_ROUND), last.map { it.round }.toSet())
        }
    }
}
