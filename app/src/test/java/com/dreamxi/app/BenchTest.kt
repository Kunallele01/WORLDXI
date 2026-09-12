package com.dreamxi.app

import com.dreamxi.app.feature.draft.BenchSelection
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.VisitedSquad
import com.dreamxi.app.sim.MatchReporter
import com.dreamxi.app.sim.MatchResult
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SquadProfile
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bench, and the rotation it brings with it.
 *
 * WHAT IT IS FOR. Eleven players taking every minute of all 38 matches took
 * every goal their side scored, so a drafted forward out-scored his real self
 * by about half. Real squads give the top eleven 69.6% of the minutes and the
 * rest to a bench that takes 27.7% of the goals. These tests check that the
 * selection is sane and that the rotation actually moves the numbers back.
 */
class BenchTest {

    private var nextId = 1L

    private fun player(
        name: String,
        role: String,
        ovr: Int,
        goals: Int = 4,
        minutes: Int = 2400,
    ): SquadPlayer {
        val grid = mapOf(
            "cb" to if (role == "CB") ovr else ovr - 20,
            "fb" to if (role == "FB") ovr else ovr - 18,
            "dm" to if (role == "DM") ovr else ovr - 15,
            "cm" to if (role == "CM") ovr else ovr - 12,
            "cam" to if (role == "CAM") ovr else ovr - 10,
            "winger" to if (role == "Winger") ovr else ovr - 12,
            "st" to if (role == "ST") ovr else ovr - 14,
        )
        return SquadPlayer(
            playerSeasonStatId = nextId,
            playerId = nextId++,
            fullName = name,
            role = role,
            group = com.dreamxi.app.ui.theme.PlayerPosition.MIDFIELDER,
            overallRating = ovr,
            minutes = minutes,
            goals = goals,
            assists = 3,
            yellowCards = 4,
            redCards = 0,
            npxg90 = 0.15,
            positionRatings = grid,
        )
    }

    private val roles = listOf("GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST")

    /** Eleven visited squads, each with a spread of positions and quality. */
    private fun visited(): List<VisitedSquad> = (1..11).map { club ->
        VisitedSquad(
            clubName = "Club $club",
            seasonLabel = "2023/24",
            players = roles.flatMap { role ->
                (1..3).map { n -> player("C$club $role$n", role, 88 - club - n * 2) }
            },
        )
    }

    private fun xi(pool: List<VisitedSquad>, formation: com.dreamxi.app.feature.draft.Formation) =
        formation.slots.mapIndexed { index, slot ->
            val squad = pool[index % pool.size]
            val chosen = squad.players.first {
                (it.role == "GK") == slot.isGoalkeeper && it.role == (if (slot.isGoalkeeper) "GK" else slot.role)
            }
            DraftedPlayer(slot, chosen, squad.clubName, squad.seasonLabel)
        }

    @Test
    fun `the bench has eight players and no goalkeeper`() {
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = visited()
        val bench = BenchSelection.pick(pool, xi(pool, formation), formation)
        assertEquals(SimModel.BENCH_SIZE, bench.size)
        assertTrue("a keeper reached the bench", bench.none { it.player.role == "GK" })
    }

    @Test
    fun `nobody is on the bench who is already in the XI, and nobody twice`() {
        val formation = Formations.first { it.id == "4-2-3-1" }
        val pool = visited()
        val starters = xi(pool, formation)
        val bench = BenchSelection.pick(pool, starters, formation)

        val startingIds = starters.map { it.player.playerId }.toSet()
        assertTrue("a starter is also a sub", bench.none { it.player.playerId in startingIds })
        assertEquals(
            "the same person is on the bench twice",
            bench.size, bench.map { it.player.playerId }.toSet().size,
        )
    }

    @Test
    fun `the bench covers the formation rather than a fixed list`() {
        // The reason the roles are derived: a 3-5-2 needs wing-back cover that a
        // fixed CB/FB/DM/CM/ST/winger list would not provide in the same
        // proportions as a 4-3-3.
        val backThree = Formations.first { it.id == "3-5-2" }
        val backFour = Formations.first { it.id == "4-3-3" }
        val threeRoles = BenchSelection.benchRoles(backThree)
        val fourRoles = BenchSelection.benchRoles(backFour)
        println("3-5-2 bench: $threeRoles")
        println("4-3-3 bench: $fourRoles")

        assertEquals(SimModel.BENCH_SIZE, threeRoles.size)
        assertTrue("no keeper cover on the bench", threeRoles.none { it == "GK" })
        assertTrue(
            "the two shapes produced the same bench",
            threeRoles.groupingBy { it }.eachCount() != fourRoles.groupingBy { it }.eachCount(),
        )
        // Every outfield role a formation fields should get cover before any
        // role gets a second body.
        val fielded = backFour.slots.filterNot { it.isGoalkeeper }.map { it.role }.toSet()
        assertTrue("a fielded role has no cover", fourRoles.toSet().containsAll(fielded))
    }

    @Test
    fun `cover is picked on the rating at that position, not raw overall`() {
        // A 90-rated striker is not the best available centre-back, however high
        // his overall is. The EA grid is what decides.
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = listOf(
            VisitedSquad(
                "Club A", "2023/24",
                listOf(
                    player("Superstar Forward", "ST", 92),
                    player("Solid Defender", "CB", 80),
                    player("Solid Full-back", "FB", 79),
                    player("Solid Anchor", "DM", 78),
                    player("Solid Midfielder", "CM", 78),
                    player("Solid Playmaker", "CAM", 78),
                    player("Solid Winger", "Winger", 78),
                    player("Backup Forward", "ST", 77),
                ),
            ),
        )
        val bench = BenchSelection.pick(pool, emptyList(), formation)
        val centreBackCover = bench.first { it.role == "CB" }
        println("CB cover chosen: ${centreBackCover.player.fullName}")
        assertEquals("Solid Defender", centreBackCover.player.fullName)
    }

    @Test
    fun `a player who actually plays the position beats a better one who does not`() {
        // The fault this closes. Ranking on adjusted rating alone sent 29% of
        // bench slots to a converted player and produced Lewandowski as winger
        // cover and Valverde at full-back: EA rates a 90-rated striker 85 as a
        // winger, and a four-point penalty never beats a six-point rating gap.
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = listOf(
            VisitedSquad(
                "Club A", "2023/24",
                listOf(
                    player("Elite Striker", "ST", 91),
                    player("Elite Midfielder", "CM", 90),
                    player("Ordinary Winger", "Winger", 78),
                    player("Ordinary Full-back", "FB", 76),
                    player("Ordinary Centre-back", "CB", 77),
                    player("Ordinary Anchor", "DM", 75),
                ),
            ),
        )
        val bench = BenchSelection.pick(pool, emptyList(), formation)
        val winger = bench.first { it.role == "Winger" }
        val fullBack = bench.first { it.role == "FB" }
        println("winger cover: ${winger.player.fullName}, full-back cover: ${fullBack.player.fullName}")
        assertEquals("Ordinary Winger", winger.player.fullName)
        assertEquals("Ordinary Full-back", fullBack.player.fullName)
    }

    @Test
    fun `a conversion is still allowed when nobody natural is left`() {
        // Preference, not a rule. A thin pool must never leave a bench slot
        // empty just because the right sort of player has run out.
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = listOf(
            VisitedSquad("Club A", "2023/24", listOf(
                player("Only Winger", "Winger", 80),
                player("Spare Midfielder", "CM", 79),
                player("Another Midfielder", "CM", 78),
            )),
        )
        val bench = BenchSelection.pick(pool, emptyList(), formation)
        assertEquals("every available player should be used", 3, bench.size)
        assertTrue(
            "a conversion should have filled a slot nobody natural could",
            bench.any { it.player.role != it.role },
        )
    }

    @Test
    fun `a corrupt positional grid cannot drag a player out of position`() {
        // About 1.2% of player-seasons carry a grid matched to the wrong person.
        // Dani Carvajal, a right-back, has four seasons rating him 64 at
        // full-back and 83 at attacking midfield — which is what put him on a
        // bench as a defensive midfielder. Preferring the recorded position
        // makes the bench immune to that whole class of data fault.
        val corrupt = player("Corrupted Right-back", "FB", 84).copy(
            positionRatings = mapOf(
                "cb" to 56, "fb" to 64, "dm" to 68,
                "cm" to 80, "cam" to 83, "winger" to 81, "st" to 75,
            ),
        )
        // The pool must be deep enough for the DM slot to be REACHED: bench
        // roles fill in order, and a two-man pool is exhausted long before it.
        // A better natural full-back also has to exist, or the corrupt player
        // would simply take the full-back slot he belongs in and never be a
        // candidate at defensive midfield at all.
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = listOf(
            VisitedSquad("Club A", "2023/24", listOf(
                corrupt,
                player("Better Full-back", "FB", 86),
                player("Real Centre-back", "CB", 80),
                player("Real Midfielder", "CM", 80),
                player("Real Winger", "Winger", 80),
                player("Real Anchor", "DM", 74),
            )),
        )
        val bench = BenchSelection.pick(pool, emptyList(), formation)
        val anchor = bench.first { it.role == "DM" }
        println("DM cover: ${anchor.player.fullName} (corrupt player rated 84, real anchor 74)")
        assertEquals("Real Anchor", anchor.player.fullName)
        // He does still end up somewhere — this pool has no natural striker, so
        // the fallback puts him there. That is the intended behaviour and the
        // test above asserts it. What must never happen is him being preferred
        // at a position where somebody who plays it was available.
        assertTrue(
            "a natural was passed over for him",
            bench.none { it.player.fullName == "Corrupted Right-back" && it.role == "DM" },
        )
    }

    @Test
    fun `a thin pool degrades rather than crashing`() {
        val formation = Formations.first { it.id == "4-3-3" }
        val pool = listOf(
            VisitedSquad("Club A", "2023/24", listOf(player("Only Man", "CM", 80))),
        )
        val bench = BenchSelection.pick(pool, emptyList(), formation)
        assertEquals("should take what little there is", 1, bench.size)
    }

    // ---------------------------------------------- what rotation actually does

    // Names must not collide between the two groups, or a test that measures
    // "how much did the BENCH score" silently counts the starters as well.
    private fun slot(role: String, goals90: Double, tag: String, ovr: Int = 85) =
        SimSlot(role, SimPlayer("$tag $role", role, 0.3, ovr, goals90 = goals90, yellow90 = 0.2))

    private fun starters() = listOf(
        SimSlot("GK", SimPlayer("Keeper", "GK", 0.0, 85, 70.0, yellow90 = 0.06)),
        slot("CB", 0.04, "Starter1"), slot("CB", 0.03, "Starter2"),
        slot("FB", 0.01, "Starter3"), slot("FB", 0.02, "Starter4"),
        slot("DM", 0.03, "Starter5"), slot("CM", 0.07, "Starter6"),
        slot("CM", 0.09, "Starter7"), slot("Winger", 0.24, "Starter8"),
        slot("Winger", 0.26, "Starter9"), slot("ST", 0.55, "Starter10"),
    )

    private fun leadStriker() = "Starter10 ST"

    // Ratings VARY, because the bench is ordered by them. Eight subs all rated
    // 85 made the ordering arbitrary, which parked the only substitute who
    // scores in the slot that plays least and dragged the bench's goal share
    // down to 20%. A real bench does not have eight identical players in it.
    private fun bench() = listOf(
        slot("CB", 0.03, "Sub1", ovr = 84), slot("FB", 0.02, "Sub2", ovr = 81),
        slot("DM", 0.03, "Sub3", ovr = 86), slot("CM", 0.06, "Sub4", ovr = 83),
        slot("CAM", 0.12, "Sub5", ovr = 85), slot("Winger", 0.18, "Sub6", ovr = 82),
        slot("ST", 0.40, "Sub7", ovr = 87), slot("CM", 0.05, "Sub8", ovr = 79),
    )

    /** Distributes a season's worth of goals and returns each player's tally. */
    private fun seasonTally(profile: SquadProfile, goals: Int, seed: Int): Map<String, Int> {
        val random = Random(seed)
        val tally = mutableMapOf<String, Int>()
        var remaining = goals
        while (remaining > 0) {
            val n = minOf(remaining, 2)
            val events = MatchReporter.report(
                "us", profile, "them", SquadProfile.EMPTY,
                MatchResult(n, 0, n.toDouble(), 0.0), random,
            )
            for (g in events.goals) tally[g.scorer] = (tally[g.scorer] ?: 0) + 1
            remaining -= n
        }
        return tally
    }

    @Test
    fun `rotation brings a star striker's tally back towards reality`() {
        // THE POINT OF THE WHOLE FEATURE. The same XI scoring the same 80 goals,
        // with and without a bench sharing the minutes.
        val withoutBench = SquadProfile.fromSquad(starters(), emptyList())
        val withBench = SquadProfile.fromSquad(starters(), bench())

        // Averaged over twenty seasons. A single season is a Poisson draw with a
        // standard deviation of about four goals, which is large enough to hide
        // the very effect this test exists to measure.
        val seeds = 1..20
        val before = seeds.map { seasonTally(withoutBench, goals = 80, seed = it)[leadStriker()] ?: 0 }
        val after = seeds.map { seasonTally(withBench, goals = 80, seed = it)[leadStriker()] ?: 0 }
        val meanBefore = before.average()
        val meanAfter = after.average()
        println(
            "lead striker over 20 seasons: ${"%.1f".format(meanBefore)} goals with no bench, " +
                "${"%.1f".format(meanAfter)} with one",
        )

        assertTrue(
            "the bench should take a real share off him: $meanBefore -> $meanAfter",
            meanAfter < meanBefore - 4,
        )
        assertTrue("but he must still lead the line: $meanAfter", meanAfter >= 18)
    }

    @Test
    fun `the bench takes roughly the share real benches take`() {
        // Measured: players outside a club's top eleven score 27.7% of its goals.
        val profile = SquadProfile.fromSquad(starters(), bench())
        val benchNames = bench().map { it.player.name }.toSet()
        val tally = seasonTally(profile, goals = 800, seed = 9)
        val benchGoals = tally.filterKeys { it in benchNames }.values.sum()
        val share = benchGoals / 800.0
        println("bench share of goals: ${"%.1f".format(share * 100)}% (real 27.7%)")
        assertTrue("bench took $share", kotlin.math.abs(share - 0.277) < 0.10)
    }

    @Test
    fun `a squad always adds up to eleven players' worth of a season`() {
        // The constraint that sets the LEVEL of every share. The measured curves
        // describe squads of twenty-five; a drafted squad is nineteen, so the
        // scaling has to balance or the side is quietly playing with ten men or
        // twelve.
        for (benchSize in listOf(0, 3, 8, 12)) {
            val roles = listOf("GK", "CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
            val minutes = SimModel.squadMinuteShares(roles, benchSize)
            val total = minutes.starters.sum() + minutes.bench.sum()
            assertEquals("bench of $benchSize", SimModel.SQUAD_PLAYER_SEASONS, total, 0.02)
            assertTrue("nobody may play more than a full season", (minutes.starters + minutes.bench).all { it <= 1.0001 })
            assertTrue("nobody may play negative minutes", (minutes.starters + minutes.bench).all { it >= 0.0 })
        }
    }

    @Test
    fun `the keeper is rotated least and the striker most`() {
        val roles = listOf("GK", "CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
        val minutes = SimModel.squadMinuteShares(roles, 8).starters
        val byRole = roles.zip(minutes).toMap()
        println("starter shares: " + byRole.entries.joinToString { "${it.key} ${"%.0f".format(it.value * 100)}%" })
        assertTrue("keeper ${byRole.getValue("GK")}", byRole.getValue("GK") > byRole.getValue("CB"))
        assertTrue("centre-back vs striker", byRole.getValue("CB") > byRole.getValue("ST"))
    }

    @Test
    fun `the bench is a hierarchy rather than eight equal names`() {
        // A real bench runs from 44% of a season down to 21%. Flat shares gave
        // eight interchangeable tallies, which no squad list ever looks like.
        val roles = listOf("GK", "CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
        val benchShares = SimModel.squadMinuteShares(roles, 8).bench
        println("bench shares: " + benchShares.joinToString { "${"%.0f".format(it * 100)}%" })
        assertEquals(8, benchShares.size)
        benchShares.zipWithNext { a, b -> assertTrue("the curve must descend", a >= b) }
        assertTrue(
            "first sub ${benchShares.first()} vs last ${benchShares.last()}",
            benchShares.first() > benchShares.last() * 1.7,
        )
    }

    @Test
    fun `the best substitute plays the most`() {
        val strong = slot("ST", 0.40, "Best", ovr = 88)
        val weak = slot("CB", 0.03, "Worst", ovr = 70)
        // Deliberately passed worst-first, so only the ranking can put it right.
        val profile = SquadProfile.fromSquad(starters(), listOf(weak, strong) + bench().drop(2))
        val best = profile.players.first { it.name == "Best ST" }
        val worst = profile.players.first { it.name == "Worst CB" }
        assertTrue(
            "the stronger sub should carry more weight: ${best.yellows} vs ${worst.yellows}",
            best.yellows > worst.yellows,
        )
    }

    @Test
    fun `every goal is still credited to somebody`() {
        val profile = SquadProfile.fromSquad(starters(), bench())
        val tally = seasonTally(profile, goals = 200, seed = 3)
        assertEquals(200, tally.values.sum())
    }

    @Test
    fun `the keeper plays every minute even with a bench`() {
        // There is no backup keeper, so his share must not be cut.
        val withBench = SquadProfile.fromSquad(starters(), bench())
        val keeper = withBench.players.first { it.name == "Keeper" }
        val withoutBench = SquadProfile.fromSquad(starters(), emptyList())
        val keeperAlone = withoutBench.players.first { it.name == "Keeper" }
        assertEquals(keeperAlone.yellows, keeper.yellows, 1e-9)
    }

    @Test
    fun `team card totals stay at the real league rate`() {
        // Rotation must not inflate a squad's discipline record just because
        // there are more names in it.
        val profile = SquadProfile.fromSquad(starters(), bench())
        println("team yellows per match with a bench: ${"%.2f".format(profile.yellowsPerMatch)}")
        assertTrue(
            "yellows per match was ${profile.yellowsPerMatch}",
            kotlin.math.abs(profile.yellowsPerMatch - SimModel.TEAM_YELLOWS_PER_MATCH) < 0.7,
        )
    }
}
