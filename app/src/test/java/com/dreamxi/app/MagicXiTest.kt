package com.dreamxi.app

import com.dreamxi.app.feature.draft.DefaultFormation
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.sim.Hungarian
import com.dreamxi.app.feature.freemode.MagicCandidate
import com.dreamxi.app.feature.freemode.MagicXi
import com.dreamxi.app.ui.theme.PlayerPosition
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Free Mode magic button — the strongest eleven a whole league can field.
 *
 * The two things worth testing are that the assignment is genuinely OPTIMAL
 * (a greedy fill is the thing this exists to beat, and it would pass any
 * loose "the team looks strong" assertion), and that the deliberate randomness
 * cannot smuggle in a weak side.
 */
class MagicXiTest {

    private var nextId = 1L

    private fun player(
        name: String,
        role: String,
        ovr: Int,
        grid: Map<String, Int>? = null,
    ): SquadPlayer = SquadPlayer(
        playerSeasonStatId = nextId,
        playerId = nextId++,
        fullName = name,
        role = role,
        group = PlayerPosition.MIDFIELDER,
        overallRating = ovr,
        minutes = 2400,
        goals = 5,
        assists = 3,
        yellowCards = 3,
        redCards = 0,
        npxg90 = 0.2,
        positionRatings = grid ?: mapOf(
            "cb" to if (role == "CB") ovr else ovr - 20,
            "fb" to if (role == "FB") ovr else ovr - 18,
            "dm" to if (role == "DM") ovr else ovr - 15,
            "cm" to if (role == "CM") ovr else ovr - 12,
            "cam" to if (role == "CAM") ovr else ovr - 10,
            "winger" to if (role == "Winger") ovr else ovr - 12,
            "st" to if (role == "ST") ovr else ovr - 14,
        ),
    )

    private fun candidate(p: SquadPlayer, club: String = "Club", season: String = "2023/24") =
        MagicCandidate(p, clubSeasonId = p.playerId, clubName = club, seasonLabel = season)

    private val roles = listOf("GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST")

    /** A league: five plausible options in every position. */
    private fun league(): List<MagicCandidate> = roles.flatMap { role ->
        (1..5).map { n -> candidate(player("$role$n", role, 90 - n * 2), club = "Club $n") }
    }

    @Test
    fun `fills every shirt exactly once, with nobody used twice`() {
        val formation = DefaultFormation
        val xi = MagicXi.build(league(), formation, Random(1))
        assertEquals(formation.slots.size, xi.size)
        assertEquals(xi.size, xi.map { it.candidate.player.playerId }.toSet().size)
        assertEquals(formation.slots.map { it.id }.toSet(), xi.map { it.slot.id }.toSet())
    }

    @Test
    fun `the goalkeeper shirt takes a keeper and no outfielder does`() {
        val formation = DefaultFormation
        val xi = MagicXi.build(league(), formation, Random(2))
        for (pick in xi) {
            assertEquals(
                "slot ${pick.slot.id}",
                pick.slot.isGoalkeeper,
                pick.candidate.player.role == "GK",
            )
        }
    }

    @Test
    fun `one keeper in the league still fills the shirt rather than leaving it empty`() {
        val formation = DefaultFormation
        val pool = league().filter { it.player.role != "GK" } +
            candidate(player("Only Keeper", "GK", 62))
        val xi = MagicXi.build(pool, formation, Random(3))
        assertEquals(formation.slots.size, xi.size)
        assertEquals("Only Keeper", xi.single { it.slot.isGoalkeeper }.candidate.player.fullName)
    }

    /**
     * The case that decided the whole design. Filling shirts in order takes the
     * best winger for the first wing, leaving the second-best forward to be
     * squeezed in somewhere at a penalty. Chosen together, both play wide.
     */
    @Test
    fun `beats a greedy slot-by-slot fill`() {
        val formation = DefaultFormation
        // Two brilliant wide forwards, and a merely good specialist winger.
        val wideGrid = mapOf(
            "cb" to 45, "fb" to 55, "dm" to 60, "cm" to 78,
            "cam" to 86, "winger" to 88, "st" to 90,
        )
        val pool = league() +
            candidate(player("Great A", "ST", 94, wideGrid)) +
            candidate(player("Great B", "ST", 93, wideGrid))

        val magic = MagicXi.build(pool, formation, Random(4))
        val greedy = greedyFill(pool, formation)
        assertTrue(
            "magic ${magic.sumOf { it.effectiveRating }} should beat greedy $greedy",
            magic.sumOf { it.effectiveRating } >= greedy,
        )
    }

    /** The same fill the design rejected, kept here purely as the benchmark. */
    private fun greedyFill(pool: List<MagicCandidate>, formation: com.dreamxi.app.feature.draft.Formation): Int {
        val used = mutableSetOf<Long>()
        var total = 0
        for (slot in formation.slots) {
            val best = pool.filter { it.player.playerId !in used }
                .mapNotNull { c -> MagicXi.ratingAt(c.player, slot)?.let { c to it } }
                .maxByOrNull { it.second } ?: continue
            used += best.first.player.playerId
            total += best.second
        }
        return total
    }

    // ---------------------------------------------------------------------
    // Filling in around shirts the user picked himself.
    // ---------------------------------------------------------------------

    @Test
    fun `kept shirts are left alone and the rest are filled`() {
        val formation = DefaultFormation
        val keeper = formation.slots.first { it.isGoalkeeper }
        val striker = formation.slots.first { it.role == "ST" }
        val mine = player("My Striker", "ST", 70)
        val xi = MagicXi.build(
            candidates = league() + candidate(mine),
            formation = formation,
            random = Random(20),
            keptSlotIds = setOf(keeper.id, striker.id),
            keptPlayerIds = setOf(mine.playerId),
        )
        assertEquals(formation.slots.size - 2, xi.size)
        assertTrue("kept shirts must not be re-solved",
            xi.none { it.slot.id == keeper.id || it.slot.id == striker.id })
        assertTrue("a kept man must not be offered a second shirt",
            xi.none { it.candidate.player.playerId == mine.playerId })
    }

    /**
     * The reason keeping is part of the solve rather than a filter afterwards:
     * the free shirts are chosen KNOWING who is already on the pitch.
     *
     * A brilliant forward who can play wide is worth 94 at his own position and
     * 92 on a wing (the two-point cost of playing out of position). With BOTH
     * wings free the solver puts him on one — that is the greedy test above.
     * With one wing already taken by the user, the arithmetic flips: 94 up
     * front plus an 88-rated specialist on the remaining wing beats 92 wide
     * plus an 88 striker. So the same player moves because of a shirt he was
     * never eligible for, which is exactly what "solved around" means.
     *
     * An earlier version of this test asserted he stayed wide, and passed a
     * "kept" player who was not in the pool at all — so it tested nothing and
     * was wrong about the rest.
     */
    @Test
    fun `the free shirts are solved around the kept ones`() {
        val formation = DefaultFormation
        val wings = formation.slots.filter { it.role == "Winger" }
        val wideGrid = mapOf(
            "cb" to 45, "fb" to 55, "dm" to 60, "cm" to 78,
            "cam" to 86, "winger" to 88, "st" to 90,
        )
        val star = player("Wide Star", "ST", 94, wideGrid)
        val keptWinger = player("Winger1", "Winger", 88)
        val pool = league() + candidate(star) + candidate(keptWinger)
        val xi = MagicXi.build(
            candidates = pool, formation = formation, random = Random(21),
            keptSlotIds = setOf(wings.first().id),
            keptPlayerIds = setOf(keptWinger.playerId),
        )
        assertTrue("the kept man must not be handed a second shirt",
            xi.none { it.candidate.player.playerId == keptWinger.playerId })
        val starPick = xi.single { it.candidate.player.fullName == "Wide Star" }
        assertEquals("ST", starPick.slot.role)
        assertEquals(94, starPick.effectiveRating)
    }

    @Test
    fun `keeping every shirt leaves nothing to solve`() {
        val formation = DefaultFormation
        val xi = MagicXi.build(
            candidates = league(), formation = formation, random = Random(22),
            keptSlotIds = formation.slots.map { it.id }.toSet(),
        )
        assertEquals(emptyList<Any>(), xi)
    }

    @Test
    fun `a player is taken in his best season, not merely his first`() {
        val formation = DefaultFormation
        val weak = player("Star", "ST", 80)
        val strong = weak.copy(playerSeasonStatId = 999, overallRating = 95)
        val pool = league() +
            candidate(weak, season = "2016/17") +
            candidate(strong, season = "2019/20")
        val xi = MagicXi.build(pool, formation, Random(5))
        val star = xi.single { it.candidate.player.fullName == "Star" }
        assertEquals("2019/20", star.candidate.seasonLabel)
    }

    @Test
    fun `the shuffle changes the side without weakening it`() {
        val formation = DefaultFormation
        // Near-equals in every position: the case the jitter exists for. A
        // league whose options are two full points apart cannot shuffle, and
        // asserting on one would only prove the test data was wrong.
        val pool = roles.flatMap { role ->
            (1..4).map { n -> candidate(player("$role$n", role, 88), club = "Club $n") }
        }
        val best = MagicXi.build(pool, formation, Random(0)).sumOf { it.effectiveRating }
        val teams = (1..40).map { seed -> MagicXi.build(pool, formation, Random(seed)) }
        // It genuinely varies...
        val distinct = teams.map { t -> t.map { it.candidate.player.playerId }.sorted() }.toSet()
        assertTrue("expected some variety, got $distinct", distinct.size > 1)
        // ...but never by more than the jitter can justify.
        val slack = (formation.slots.size * MagicXi.JITTER).toInt() + 1
        for (t in teams) {
            assertTrue(
                "a shuffled side fell too far: ${t.sumOf { it.effectiveRating }} vs $best",
                t.sumOf { it.effectiveRating } >= best - slack,
            )
        }
    }

    @Test
    fun `an empty league yields no picks rather than throwing`() {
        val formation = DefaultFormation
        assertEquals(emptyList<Any>(), MagicXi.build(emptyList(), formation, Random(6)))
    }

    /**
     * The solver itself, against exhaustive search on small random matrices.
     * Hungarian is easy to get subtly wrong and the error would look like a
     * merely unlucky team rather than a bug.
     */
    @Test
    fun `hungarian matches brute force`() {
        val rng = Random(7)
        repeat(200) {
            val rows = 1 + rng.nextInt(4)
            val cols = rows + rng.nextInt(4)
            val m = Array(rows) { DoubleArray(cols) { rng.nextInt(-5, 20).toDouble() } }
            // Scatter some forbidden pairings.
            repeat(rng.nextInt(3)) {
                m[rng.nextInt(rows)][rng.nextInt(cols)] = Double.NEGATIVE_INFINITY
            }
            val got = Hungarian.maximise(m)
            val used = got.filter { it >= 0 }
            assertEquals("columns reused", used.size, used.toSet().size)
            val filled = used.size
            val total = got.withIndex().filter { it.value >= 0 }.sumOf { m[it.index][it.value] }
            assertEquals("not optimal for $rows x $cols", bruteForce(m), filled to total)
        }
    }

    /**
     * Every shirt filled first, then the highest total — NOT the highest total
     * outright.
     *
     * This distinction is the whole reason the reference exists. Allowed to
     * leave a shirt empty, the best-scoring answer to
     *
     *     row 0: [-4, -5]        row 1: [-5, forbidden]
     *
     * is -4, by fielding one player and abandoning the second slot. That is a
     * better NUMBER and a worse team. An eleven with a hole in it is not a
     * team, so cardinality is ranked ahead of quality — and the solver, which
     * fills every shirt it can, was right where an earlier version of this
     * test was wrong.
     */
    private fun bruteForce(m: Array<DoubleArray>): Pair<Int, Double> {
        val rows = m.size
        val cols = m[0].size
        var best = -1 to Double.NEGATIVE_INFINITY
        fun better(a: Pair<Int, Double>, b: Pair<Int, Double>) =
            if (a.first != b.first) a.first > b.first else a.second > b.second
        fun walk(row: Int, used: Set<Int>, total: Double, filled: Int) {
            if (row == rows) {
                val here = filled to total
                if (better(here, best)) best = here
                return
            }
            for (c in 0 until cols) {
                if (c in used || m[row][c] == Double.NEGATIVE_INFINITY) continue
                walk(row + 1, used + c, total + m[row][c], filled + 1)
            }
            walk(row + 1, used, total, filled)
        }
        walk(0, emptySet(), 0.0, 0)
        return best
    }

    /**
     * The ladder a tap walks. Its contract is narrower than the solver's: it
     * must be TOTAL and stable, because a tap that could return the man it
     * just replaced would read as the button being broken.
     */
    @Test
    fun `the ladder is ordered best first, one rung per person`() {
        val slot = DefaultFormation.slots.first { it.role == "CB" }
        val pool = league()
        val ladder = MagicXi.ladder(pool, slot)
        assertEquals(
            ladder.map { it.candidate.player.playerId }.size,
            ladder.map { it.candidate.player.playerId }.toSet().size,
        )
        assertEquals(
            ladder.map { it.effectiveRating },
            ladder.map { it.effectiveRating }.sortedDescending(),
        )
        // A keeper can never appear on an outfield rung.
        assertTrue(ladder.none { it.candidate.player.role == "GK" })
        assertEquals("CB1", ladder.first().candidate.player.fullName)
    }

    /**
     * The bug this ladder was rewritten for. Ranked on effective rating alone,
     * a brilliant forward carrying an out-of-position penalty still outranks
     * the twelfth-best specialist, so tapping a defensive shirt enough times
     * started offering strikers. A tap asks who ELSE plays here, not who else
     * could survive here.
     */
    @Test
    fun `tapping a defensive shirt never offers a forward`() {
        val slot = DefaultFormation.slots.first { it.role == "CB" }
        // Far better than any centre-back in the league, and still not a
        // centre-back.
        val pool = league() + candidate(player("Superstar", "ST", 99))
        val ladder = MagicXi.ladder(pool, slot)
        assertTrue(
            "a striker reached a CB ladder: ${ladder.map { it.candidate.player.fullName }}",
            ladder.none { it.candidate.player.role != "CB" },
        )
    }

    /**
     * ...unless the cast itself put him there, in which case he stays on his
     * own cycle so that walking it returns to the magic XI.
     */
    @Test
    fun `the cast pick stays on the ladder and the cycle returns to him`() {
        val slot = DefaultFormation.slots.first { it.role == "CB" }
        val odd = candidate(player("Emergency Striker", "ST", 99))
        val pool = league() + odd
        val ladder = MagicXi.ladder(pool, slot, keep = odd)
        assertEquals("Emergency Striker", ladder.first().candidate.player.fullName)
        assertTrue(ladder.drop(1).none { it.candidate.player.role != "CB" })

        // Tap all the way round and he comes back.
        var current = odd.player.playerId
        repeat(ladder.size) {
            val here = ladder.indexOfFirst { it.candidate.player.playerId == current }
            current = ladder[(here + 1) % ladder.size].candidate.player.playerId
        }
        assertEquals(odd.player.playerId, current)
    }

    @Test
    fun `the ladder is identical across calls`() {
        val slot = DefaultFormation.slots.first { it.role == "CM" }
        val pool = league()
        assertEquals(
            MagicXi.ladder(pool, slot).map { it.candidate.player.playerId },
            MagicXi.ladder(pool.shuffled(Random(11)), slot).map { it.candidate.player.playerId },
        )
    }

    @Test
    fun `a player already standing elsewhere is off the ladder`() {
        val slot = DefaultFormation.slots.first { it.role == "CB" }
        val pool = league()
        val best = MagicXi.ladder(pool, slot).first().candidate.player.playerId
        val without = MagicXi.ladder(pool, slot, exclude = setOf(best))
        assertTrue(without.none { it.candidate.player.playerId == best })
        assertEquals("CB2", without.first().candidate.player.fullName)
    }

    /**
     * Tapping enough times comes back round to the man you started with, and
     * never lands on him early. This is the whole cycle, run in the test the
     * way the ViewModel runs it.
     */
    @Test
    fun `tapping walks every rung once and wraps`() {
        val slot = DefaultFormation.slots.first { it.role == "ST" }
        val pool = league()
        val ladder = MagicXi.ladder(pool, slot)
        var current = ladder.first().candidate.player.playerId
        val seen = mutableListOf(current)
        repeat(ladder.size - 1) {
            val here = ladder.indexOfFirst { it.candidate.player.playerId == current }
            current = ladder[(here + 1) % ladder.size].candidate.player.playerId
            seen += current
        }
        assertEquals("every rung exactly once", ladder.size, seen.toSet().size)
        val here = ladder.indexOfFirst { it.candidate.player.playerId == current }
        assertEquals(
            "wraps back to the start",
            ladder.first().candidate.player.playerId,
            ladder[(here + 1) % ladder.size].candidate.player.playerId,
        )
    }

    @Test
    fun `an empty shirt takes the best man on its first tap`() {
        val slot = DefaultFormation.slots.first { it.role == "CB" }
        val ladder = MagicXi.ladder(league(), slot)
        // -1 is "nobody here"; the ViewModel adds one and lands on rung zero.
        assertEquals(ladder.first().candidate.player.fullName, ladder[(-1 + 1) % ladder.size].candidate.player.fullName)
    }

    @Test
    fun `out of position is priced, not forbidden`() {
        val formation = DefaultFormation
        // A brilliant CAM in a formation with no CAM shirt. He should still
        // take a central midfield place ahead of a mediocre specialist.
        val pool = roles.filter { it != "CM" && it != "CAM" }
            .flatMap { role -> (1..3).map { n -> candidate(player("$role$n", role, 88 - n * 2)) } } +
            (1..3).map { n -> candidate(player("CM$n", "CM", 70 - n)) } +
            candidate(player("Playmaker", "CAM", 91))
        val xi = MagicXi.build(pool, formation, Random(8))
        val cm = xi.filter { it.slot.role == "CM" }.map { it.candidate.player.fullName }
        assertTrue("expected the CAM to fill a CM shirt, got $cm", "Playmaker" in cm)
        assertNotEquals(0, xi.size)
    }
}
