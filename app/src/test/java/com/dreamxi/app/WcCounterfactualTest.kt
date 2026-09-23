package com.dreamxi.app

import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.WcTournamentSimulator
import com.dreamxi.app.sim.worldcup.WcUserTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's XI takes over a bottom-of-group nation, and everything he does not
 * touch must stay history.
 *
 * Run for EVERY bottom nation in every edition — 52 takeovers — because the
 * failure modes are specific: a group whose third place feeds 2026's table, a
 * bracket branch that should cascade, a nation that cannot field an XI being
 * asked to play. Each of those would appear for one takeover and not another.
 */
class WcCounterfactualTest {

    /** A strong but real side to play as: Argentina's 2018 eleven. */
    private val user = WcUserTeam(
        id = "USER",
        name = "Your XI",
        lineup = checkNotNull(WcFixtures.lineups[2018 to "Argentina"]),
    )

    @Test
    fun `every takeover keeps untouched history real and the bracket whole`() {
        var runs = 0
        var branchesRewritten = 0
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            for (replaced in data.entries.filter { it.finishedBottom }) {
                val result = WcTournamentSimulator.play(data, user, replaced.id, seed = 1000L + runs)
                runs++
                val label = "$year replacing ${replaced.id}"

                assertEquals("$label: match count", data.matches.size, result.matches.size)
                assertFalse("$label: the replaced nation still played", result.matches.any { it.involves(replaced.id) })
                assertEquals(
                    "$label: the user plays his three group games",
                    3, result.matches.count { it.round == GROUP_ROUND && it.involves(user.id) },
                )
                assertNotNull(result.championId)

                // Group games he is not in are exactly the real ones.
                for (m in result.matches.filter { it.round == GROUP_ROUND && !it.involves(user.id) }) {
                    assertFalse("$label: ${m.homeId} v ${m.awayId} was re-simulated", m.simulated)
                    val real = data.matches.single {
                        it.round == GROUP_ROUND && it.homeId == m.homeId && it.awayId == m.awayId
                    }
                    assertEquals(real.homeGoals, m.score.homeGoals)
                    assertEquals(real.awayGoals, m.score.awayGoals)
                    assertEquals(real.goals, m.goals)
                }

                // Every other group finishes in its real order.
                for ((letter, rows) in result.tables) {
                    if (letter == replaced.group) continue
                    val real = data.entries.filter { it.group == letter }.sortedBy { it.realPosition }.map { it.id }
                    assertEquals("$label: group $letter", real, rows.map { it.teamId })
                }

                // A simulated knockout tie is one that never happened.
                for (m in result.matches.filter { it.round != GROUP_ROUND && it.simulated }) {
                    val happened = data.matches.any {
                        it.round == m.round && setOf(it.homeId, it.awayId) == setOf(m.homeId, m.awayId)
                    }
                    assertFalse("$label: ${m.round} ${m.homeId} v ${m.awayId} really happened", happened)
                    if (!m.involves(user.id)) branchesRewritten++
                }
                // And a real one is the real result.
                for (m in result.matches.filter { it.round != GROUP_ROUND && !it.simulated }) {
                    assertFalse(m.involves(user.id))
                }
                // Every decided tie has a winner.
                assertTrue(result.matches.filter { it.round != GROUP_ROUND }.all { it.winnerId != null })
            }
        }
        assertEquals("every bottom nation in every edition", 52, runs)
        println("52 takeovers played; $branchesRewritten knockout ties between two real nations were rewritten by the cascade")
        assertTrue("the cascade should rewrite at least some ties between real nations", branchesRewritten > 0)
    }

    @Test
    fun `a run replays identically from its seed`() {
        val data = WcFixtures.tournaments.getValue(2014)
        val replaced = data.entries.first { it.finishedBottom }
        val a = WcTournamentSimulator.play(data, user, replaced.id, seed = 99L)
        val b = WcTournamentSimulator.play(data, user, replaced.id, seed = 99L)
        assertEquals(a, b)
    }

    @Test
    fun `a nation that did not finish bottom cannot be taken over`() {
        val data = WcFixtures.tournaments.getValue(2022)
        val champion = data.entries.first { it.id == "Argentina" }
        val failed = runCatching { WcTournamentSimulator.play(data, user, champion.id, seed = 1L) }
        assertTrue(failed.isFailure)
    }

    /**
     * Qatar hosted 2022 and finished bottom, so a user can take over a host. He
     * inherits the host's scoring lift with its fixtures — the club mode's XI
     * keeps a relegated club's home games the same way.
     */
    @Test
    fun `taking over a host inherits the host's advantage`() {
        val data = WcFixtures.tournaments.getValue(2022)
        val qatar = data.entries.single { it.id == "Qatar" }
        assertTrue(qatar.isHost && qatar.finishedBottom)
        val result = WcTournamentSimulator.play(data, user, "Qatar", seed = 5L)
        for (m in result.userMatches.filter { it.round == GROUP_ROUND }) {
            val opponentId = if (m.homeId == user.id) m.awayId else m.homeId
            val opponent = data.entries.single { it.id == opponentId }
            val userExpected = if (m.homeId == user.id) m.score.homeExpected else m.score.awayExpected
            val asHost = com.dreamxi.app.sim.worldcup.WcMatchEngine.expectedGoals(
                com.dreamxi.app.sim.worldcup.WcSide(user.lineup.rating, isHost = true),
                com.dreamxi.app.sim.worldcup.WcSide(checkNotNull(opponent.lineup).rating, opponent.isHost),
            )
            assertEquals("user v $opponentId expected goals", asHost, userExpected, 1e-9)
        }
    }

    /**
     * What a user will actually field: the best eleven available from EVERY
     * squad of every edition, cross-era, as a Magic XI would pick it. Printed
     * so its title rate is known before anyone reports it — the club mode's
     * best-ever side finishing second was exactly that kind of report.
     */
    @Test
    fun `how far the best possible cross-era XI goes`() {
        val everyone = WcFixtures.squads.values.flatten()
            .groupBy { it.id }.values.map { copies -> copies.maxBy { it.rating } }
        val lineup = checkNotNull(
            com.dreamxi.app.sim.worldcup.NationalSide.bestLineup(everyone, WcFixtures.formations),
        )
        val dream = WcUserTeam("USER", "Dream XI", lineup)
        println("dream XI: " + lineup.players.joinToString { "${it.name} ${"%.0f".format(it.rating)}" } +
            " | attack ${"%.2f".format(lineup.rating.attack)} defence ${"%.2f".format(lineup.rating.defence)}")
        val finishes = mutableMapOf<String, Int>()
        var runs = 0
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            for (replaced in data.entries.filter { it.finishedBottom }) {
                repeat(10) { i ->
                    val result = WcTournamentSimulator.play(data, dream, replaced.id, seed = (runs * 37 + i).toLong())
                    finishes.merge(checkNotNull(result.userFinish), 1, Int::plus)
                }
                runs++
            }
        }
        println("best cross-era XI as a replaced nation, 520 runs: $finishes")
        assertTrue((finishes["Champion"] ?: 0) > (finishes[GROUP_ROUND] ?: 0))
    }

    @Test
    fun `how far a strong XI goes, across every takeover`() {
        val finishes = mutableMapOf<String, Int>()
        var runs = 0
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            for (replaced in data.entries.filter { it.finishedBottom }) {
                repeat(10) { i ->
                    val result = WcTournamentSimulator.play(data, user, replaced.id, seed = (runs * 31 + i).toLong())
                    finishes.merge(checkNotNull(result.userFinish), 1, Int::plus)
                }
                runs++
            }
        }
        println("Argentina 2018's XI as a replaced nation, 520 runs: $finishes")
        assertTrue("a strong side should win some", (finishes["Champion"] ?: 0) > 0)
        assertTrue("and should not win them all", (finishes["Champion"] ?: 0) < 520)
    }
}
