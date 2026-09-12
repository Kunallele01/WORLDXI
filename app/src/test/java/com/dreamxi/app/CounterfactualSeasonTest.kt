package com.dreamxi.app

import com.dreamxi.app.sim.RealFixture
import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counterfactual season: the user's XI takes a relegated club's place and
 * NOTHING ELSE ABOUT HISTORY CHANGES.
 *
 * This replaced a full re-simulation of all 380 matches, which had the effect
 * of re-deciding the league — Barcelona winning 2021/22 when Real Madrid
 * actually did. The question a user is asking is not "what if this season were
 * replayed?" but "could my eleven have done it?", and that is only meaningful
 * against the season that really happened.
 *
 * So these tests are mostly about what must NOT move.
 */
class CounterfactualSeasonTest {

    private fun club(id: String, ovr: Int, user: Boolean = false): SimTeam {
        val xi = listOf(SimSlot("GK", SimPlayer("$id GK", "GK", 0.0, ovr, 69.0))) +
            listOf("CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
                .map { SimSlot(it, SimPlayer("$id $it", it, 0.12, ovr, goals90 = 0.15)) }
        return SimTeam(
            id = id, name = "Club $id", season = "2021/22",
            rating = TeamStrength.rate(xi), isUserTeam = user, xi = xi,
            squad = SquadProfile.fromXi(xi),
        )
    }

    private val teams = (1..20).map { club("c$it", ovr = 74 + (it * 6) / 10) }
    private val replaced = "c1"

    /** A full real season: everyone home and away, with fixed historical scores. */
    private fun realSeason(): List<RealFixture> {
        val random = Random(4242)
        val out = mutableListOf<RealFixture>()
        var matchday = 1
        for (i in teams.indices) {
            for (j in teams.indices) {
                if (i == j) continue
                out += RealFixture(
                    matchday = (matchday++ % 38) + 1,
                    homeTeamId = teams[i].id,
                    awayTeamId = teams[j].id,
                    homeGoals = random.nextInt(0, 4),
                    awayGoals = random.nextInt(0, 4),
                )
            }
        }
        return out
    }

    private fun play(userOvr: Int = 86, seed: Long = 7) = SeasonSimulator.playCounterfactual(
        real = realSeason(),
        teams = teams,
        user = club("dream-xi", userOvr, user = true),
        replacedTeamId = replaced,
        seed = seed,
    )

    @Test
    fun `every match not involving the user keeps its real scoreline`() {
        // The whole point. One changed result anywhere else and the season is
        // no longer the season that happened.
        val real = realSeason()
        val season = play()
        val byKey = real.associateBy { Triple(it.matchday, it.homeTeamId, it.awayTeamId) }

        var checked = 0
        for (f in season.fixtures) {
            if (f.home.isUserTeam || f.away.isUserTeam) continue
            val original = byKey.getValue(Triple(f.matchday, f.home.id, f.away.id))
            assertEquals(original.homeGoals, f.result.homeGoals)
            assertEquals(original.awayGoals, f.result.awayGoals)
            assertTrue("a real result was marked simulated", !f.simulated)
            checked++
        }
        assertEquals("expected 342 untouched matches", 342, checked)
    }

    @Test
    fun `the user inherits the replaced club's exact fixture list`() {
        val real = realSeason()
        val season = play()

        val theirs = real
            .filter { it.homeTeamId == replaced || it.awayTeamId == replaced }
            .map { Triple(it.matchday, it.homeTeamId == replaced, opponentOf(it)) }
            .sortedBy { it.first }
        val ours = season.fixtures
            .filter { it.home.isUserTeam || it.away.isUserTeam }
            .map { Triple(it.matchday, it.home.isUserTeam, if (it.home.isUserTeam) it.away.id else it.home.id) }
            .sortedBy { it.first }

        assertEquals("same number of matches", theirs.size, ours.size)
        assertEquals("same matchdays, venues and opponents", theirs, ours)
        assertEquals(38, ours.size)
    }

    private fun opponentOf(f: RealFixture) =
        if (f.homeTeamId == replaced) f.awayTeamId else f.homeTeamId

    @Test
    fun `only the user's own matches are simulated`() {
        val season = play()
        assertEquals(38, season.fixtures.count { it.simulated })
        assertEquals(342, season.fixtures.count { !it.simulated })
        for (f in season.fixtures.filter { it.simulated }) {
            assertTrue("a simulated match without the user", f.home.isUserTeam || f.away.isUserTeam)
        }
    }

    @Test
    fun `the replaced club is gone and the user is in its place`() {
        val season = play()
        assertTrue("the relegated club is still in the table", season.table.none { it.team.id == replaced })
        assertTrue("the user is missing from the table", season.table.any { it.team.isUserTeam })
        assertEquals("the league is still twenty clubs", 20, season.table.size)
        for (row in season.table) {
            assertEquals("${row.team.name} played ${row.played}", 38, row.played)
        }
    }

    @Test
    fun `a club's record changes only through its two matches against the user`() {
        // The strongest guarantee this design offers: history is intact except
        // where the user actually intervened. A side that took points off the
        // relegated club and none off the user drops; one that lost to them but
        // beats the user climbs. Nothing else may move.
        val real = realSeason()
        val season = play()

        for (row in season.table) {
            if (row.team.isUserTeam) continue
            val realGames = real.filter {
                (it.homeTeamId == row.team.id || it.awayTeamId == row.team.id) &&
                    it.homeTeamId != replaced && it.awayTeamId != replaced
            }
            val realPoints = realGames.sumOf { f ->
                val home = f.homeTeamId == row.team.id
                val gf = if (home) f.homeGoals else f.awayGoals
                val ga = if (home) f.awayGoals else f.homeGoals
                if (gf > ga) 3 else if (gf == ga) 1 else 0
            }
            val vsUser = season.fixtures
                .filter { it.simulated && (it.home.id == row.team.id || it.away.id == row.team.id) }
                .sumOf { f ->
                    val team = if (f.home.id == row.team.id) f.home else f.away
                    f.pointsFor(team) ?: 0
                }
            assertEquals(
                "${row.team.name}'s points drifted from history",
                realPoints + vsUser, row.points,
            )
        }
    }

    @Test
    fun `the real champion keeps the title unless the user takes it`() {
        // With the user replacing the WEAKEST side and being ordinary, the
        // league that really happened should come out the same way.
        val real = realSeason()
        val realTable = SeasonSimulator.buildTable(
            teams,
            real.map { f ->
                com.dreamxi.app.sim.SimFixture(
                    matchday = f.matchday,
                    home = teams.first { it.id == f.homeTeamId },
                    away = teams.first { it.id == f.awayTeamId },
                    result = com.dreamxi.app.sim.MatchResult(
                        f.homeGoals, f.awayGoals, f.homeGoals.toDouble(), f.awayGoals.toDouble(),
                    ),
                    simulated = false,
                )
            },
        )
        val realChampion = realTable.first { it.team.id != replaced }.team.id

        // A deliberately poor XI, so the user cannot win it themselves.
        val season = SeasonSimulator.playCounterfactual(
            real = real,
            teams = teams,
            user = club("dream-xi", 62, user = true),
            replacedTeamId = replaced,
            seed = 3,
        )
        val champion = season.table.first().team.id
        println("real champion $realChampion, counterfactual champion $champion")
        assertEquals("a weak XI rewrote the title race", realChampion, champion)
    }

    @Test
    fun `scorers are credited in the real matches too`() {
        // Otherwise the league's top-scorer list would contain only the user's
        // eleven, which is exactly what the stats screen must not show.
        val season = play()
        val realMatchGoals = season.fixtures
            .filter { !it.simulated }
            .sumOf { it.result.homeGoals + it.result.awayGoals }
        val credited = season.fixtures
            .filter { !it.simulated }
            .sumOf { it.events.goals.size }
        assertEquals(realMatchGoals, credited)
        assertTrue("no goals in the real matches to check", realMatchGoals > 0)
    }

    @Test
    fun `the same seed replays the same counterfactual`() {
        val a = play(seed = 21)
        val b = play(seed = 21)
        assertEquals(
            a.fixtures.map { it.result.toString() },
            b.fixtures.map { it.result.toString() },
        )
        assertEquals(a.table.map { it.team.id to it.points }, b.table.map { it.team.id to it.points })
    }

    @Test
    fun `a stronger XI finishes higher than a weaker one`() {
        val weak = SeasonSimulator.playCounterfactual(
            realSeason(), teams, club("dream-xi", 66, user = true), replaced, 9,
        ).userRow!!.position
        val strong = SeasonSimulator.playCounterfactual(
            realSeason(), teams, club("dream-xi", 92, user = true), replaced, 9,
        ).userRow!!.position
        println("weak XI finished $weak, strong XI finished $strong")
        assertTrue("weak $weak vs strong $strong", strong < weak)
    }
}
