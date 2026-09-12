package com.dreamxi.app

import com.dreamxi.app.sim.MatchEngine
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.TeamStrength
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine is calibrated against 200 real club-seasons, so these tests check
 * the things a correlation cannot: that a real squad produces a believable
 * season, that an absurd squad does not produce an absurd one, and that the
 * randomness behaves like football rather than like a coin.
 */
class MatchEngineTest {

    // --- Liverpool 2019/20: 97 points, 85 scored, 33 conceded ---------------
    private fun liverpool() = listOf(
        gk("Alisson", 90, 71.6),
        out("FB", "FB", "Robertson", 87, 0.041),
        out("CB", "CB", "van Dijk", 90, 0.093),
        out("CB", "CB", "Gomez", 83, 0.030),
        out("FB", "FB", "Alexander-Arnold", 87, 0.036),
        out("DM", "DM", "Fabinho", 85, 0.041),
        out("CM", "CM", "Henderson", 86, 0.062),
        out("CM", "CM", "Wijnaldum", 84, 0.070),
        out("Winger", "Winger", "Mané", 90, 0.475),
        out("ST", "ST", "Firmino", 86, 0.310),
        out("Winger", "Winger", "Salah", 90, 0.570),
    )

    // --- Norwich 2019/20: 21 points, 26 scored, 75 conceded ----------------
    private fun norwich() = listOf(
        gk("Krul", 74, 65.0),
        out("FB", "FB", "Byram", 71, 0.020),
        out("CB", "CB", "Godfrey", 72, 0.030),
        out("CB", "CB", "Hanley", 71, 0.035),
        out("FB", "FB", "Aarons", 72, 0.020),
        out("DM", "DM", "Tettey", 70, 0.030),
        out("CM", "CM", "McLean", 71, 0.050),
        out("CM", "CM", "Vrancic", 72, 0.070),
        out("Winger", "Winger", "Buendía", 76, 0.150),
        out("ST", "ST", "Pukki", 75, 0.330),
        out("Winger", "Winger", "Cantwell", 73, 0.160),
    )

    private fun gk(name: String, ovr: Int, save: Double) =
        SimSlot("GK", SimPlayer(name, "GK", 0.0, ovr, save))

    private fun out(slot: String, natural: String, name: String, ovr: Int, npxg: Double, penalty: Int = 0) =
        SimSlot(slot, SimPlayer(name, natural, npxg, ovr), penalty)

    @Test
    fun `a real title-winning squad produces a title-winning season`() {
        val lfc = TeamStrength.rate(liverpool())
        // Liverpool scored 85 and conceded 33 in 2019/20: 2.24 and 0.87 a match.
        assertTrue("attack was ${lfc.attack}", lfc.attack in 1.7..2.8)
        assertTrue("defence was ${lfc.defence}", lfc.defence in 0.5..1.3)
    }

    @Test
    fun `a real relegated squad produces a relegation season`() {
        val nor = TeamStrength.rate(norwich())
        // Norwich scored 26 and conceded 75: 0.68 and 1.97 a match.
        assertTrue("attack was ${nor.attack}", nor.attack in 0.4..1.3)
        assertTrue("defence was ${nor.defence}", nor.defence in 1.4..2.6)
    }

    @Test
    fun `the better side takes the points against the weaker one`() {
        val lfc = TeamStrength.rate(liverpool())
        val nor = TeamStrength.rate(norwich())
        val atHome = MatchEngine.expectedPoints(lfc, nor)
        val awayFromHome = MatchEngine.expectedPointsBoth(nor, lfc).second
        assertTrue("Liverpool should dominate at home: $atHome", atHome > 2.0)
        assertTrue("and still take most points away: $awayFromHome", awayFromHome > 1.5)
    }

    @Test
    fun `ten different strikers do not score five a game`() {
        // The failure this engine was designed around. A naive additive model
        // sums ten strikers' npxG and predicts an impossible attack; the
        // positional blend puts nine of them where a striker's chances never
        // arrive.
        //
        // TEN DIFFERENT MEN, not one man ten times: a draft can never place the
        // same person twice, but it can absolutely hand you a spin of City,
        // then Barcelona, then Bayern and let you take the striker each time.
        // That is the scenario this guards, so the test builds it honestly.
        var n = 0
        fun striker() = SimPlayer("Striker ${++n}", "ST", npxg90 = 0.83, overallRating = 91)
        val allStrikers = listOf(
            SimSlot("GK", SimPlayer("Keeper", "GK", 0.0, 85, 70.0)),
            SimSlot("CB", striker(), -28), SimSlot("CB", striker(), -28),
            SimSlot("FB", striker(), -23), SimSlot("FB", striker(), -23),
            SimSlot("DM", striker(), -22), SimSlot("CM", striker(), -10),
            SimSlot("CAM", striker(), -4), SimSlot("Winger", striker(), -3),
            SimSlot("Winger", striker(), -3), SimSlot("ST", striker(), 0),
        )
        val rating = TeamStrength.rate(allStrikers)
        // Manchester City's 2019/20 attack — the best in the data — produced
        // 2.68 goals a match. Ten world-class strikers should beat that and
        // not embarrass it.
        assertTrue(
            "ten strikers produced ${rating.attack} goals a match",
            rating.attack in 2.6..3.3,
        )
        // And they should be leaky, because nine of them cannot defend.
        assertTrue(
            "ten strikers conceded only ${rating.defence} a match",
            rating.defence > 1.0,
        )
    }

    @Test
    fun `a player at his own position contributes exactly his own rate`() {
        // The r=+0.864 relationship is defined on players in their natural
        // positions; the blend must leave that case untouched.
        val striker = SimPlayer("S", "ST", npxg90 = 0.55, overallRating = 79)
        val natural = SimSlot("ST", striker, positionPenalty = 0)
        assertEquals(0.55, TeamStrength.attackContribution(natural), 0.0001)
    }

    @Test
    fun `moving a striker to centre-back destroys his output`() {
        val striker = SimPlayer("S", "ST", npxg90 = 0.83, overallRating = 91)
        val atStriker = TeamStrength.attackContribution(SimSlot("ST", striker, 0))
        val atCentreBack = TeamStrength.attackContribution(SimSlot("CB", striker, -28))
        assertTrue("$atStriker -> $atCentreBack", atCentreBack < atStriker * 0.2)
        // But he is still a better attacking threat there than a median
        // centre-back, because he is a far better player.
        assertTrue(atCentreBack > 0.0)
    }

    @Test
    fun `a better goalkeeper concedes fewer goals than a worse one`() {
        fun withKeeper(ovr: Int, save: Double) =
            TeamStrength.expectedGoalsAgainst(liverpool().drop(1) + gk("K", ovr, save))
        val elite = withKeeper(90, 78.0)
        val poor = withKeeper(90, 60.0)
        assertTrue("elite $elite vs poor $poor", elite < poor)
    }

    /**
     * A superhuman XI concedes a superhuman-but-real number of goals.
     *
     * THE BUG THIS EXISTS FOR. Goals conceded used to be a straight line in
     * the XI's defensive rating, fitted on real clubs and never asked what it
     * did outside them. No real club-season in 400 rates above 86.76; Free
     * Mode's magic XI rates 88.9, and the line had it conceding 15 goals a
     * season with 28 clean sheets — better than any defence in the history of
     * either league, by a distance. Above 94.5 it conceded a NEGATIVE number.
     *
     * The bounds below are the two real anchors either side of the answer:
     * Chelsea 2004/05 hold the record at 15 in a 38-game season, and the best
     * defence in our own data is Atlético Madrid 2015/16 on 18. An XI of the
     * best players of a decade should be somewhere near the record, and must
     * not be absurdly beyond it.
     */
    @Test
    fun `an all-time XI concedes a record-breaking number of goals, not a fictional one`() {
        // Every position filled by a 90-plus player behind an elite keeper —
        // roughly what Free Mode's magic button produces.
        val superhuman = listOf(
            gk("Keeper", 91, 80.6),
            out("FB", "FB", "LB", 88, 0.041),
            out("CB", "CB", "CB1", 90, 0.093),
            out("CB", "CB", "CB2", 90, 0.070),
            out("FB", "FB", "RB", 88, 0.036),
            out("DM", "DM", "DM", 91, 0.041),
            out("CM", "CM", "CM1", 90, 0.062),
            out("CM", "CM", "CM2", 89, 0.070),
            out("Winger", "Winger", "LW", 94, 0.475),
            out("ST", "ST", "ST", 92, 0.310),
            out("Winger", "Winger", "RW", 94, 0.570),
        )
        val season = TeamStrength.expectedGoalsAgainst(superhuman) * SimModel.MATCHES_PER_SEASON
        assertTrue("conceded $season a season — beyond any real defence", season > 15.0)
        assertTrue("conceded $season a season — not actually a great defence", season < 26.0)
    }

    /**
     * The curve must never reach zero, however good the eleven. A model of a
     * count that can go negative is not a model of goals.
     */
    @Test
    fun `even eleven perfect players concede goals`() {
        val perfect = listOf(gk("Keeper", 99, 95.0)) +
            listOf("FB", "CB", "CB", "FB", "DM", "CM", "CM", "Winger", "ST", "Winger")
                .mapIndexed { i, role -> out(role, role, "P$i", 99, 0.3) }
        val perMatch = TeamStrength.expectedGoalsAgainst(perfect)
        assertTrue("conceded $perMatch a match", perMatch > 0.0)
        // And not by being clamped. Eleven 99s behind a 95% keeper land on
        // 0.22 a match — 8.4 goals a season, about half the record — which is
        // the model's own answer and sits 47% clear of the 0.15 backstop. The
        // margin asserted is deliberately looser than that, since the exact
        // figure is allowed to move when the fit is redone; what must not
        // happen is the clamp quietly becoming the thing that produces it.
        assertTrue("sitting on the clamp at $perMatch", perMatch > SimModel.MIN_EXPECTED_GOALS * 1.2)
    }

    /**
     * Diminishing returns, which is what the log link buys. The rating points
     * that take a bad defence to an average one must be worth MORE goals than
     * the ones that take a great defence to a superb one — a straight line
     * says they are worth exactly the same, which is how it walked off the
     * end of the scale.
     */
    @Test
    fun `each rating point buys less than the one before`() {
        fun at(ovr: Int) = TeamStrength.expectedGoalsAgainst(
            listOf(gk("K", ovr, SimModel.LEAGUE_AVERAGE_SAVE_PCT)) +
                listOf("FB", "CB", "CB", "FB", "DM", "CM", "CM", "Winger", "ST", "Winger")
                    .mapIndexed { i, role -> out(role, role, "P$i", ovr, 0.2) },
        )
        val cheap = at(70) - at(75)
        val dear = at(85) - at(90)
        assertTrue("70->75 saved $cheap, 85->90 saved $dear", cheap > dear)
    }

    @Test
    fun `home advantage matches the measured 1_245`() {
        val a = TeamStrength.rate(liverpool())
        val b = TeamStrength.rate(liverpool())
        val (home, away) = MatchEngine.expectedScoreline(a, b)
        assertEquals(SimModel.HOME_ADVANTAGE, home / away, 0.001)
    }

    @Test
    fun `the same seed replays the same match`() {
        // A user asking "what happened in that game" must get one answer.
        val a = TeamStrength.rate(liverpool())
        val b = TeamStrength.rate(norwich())
        val first = MatchEngine.play(a, b, Random(20192020))
        val second = MatchEngine.play(a, b, Random(20192020))
        assertEquals(first.homeGoals, second.homeGoals)
        assertEquals(first.awayGoals, second.awayGoals)
    }

    @Test
    fun `a strong favourite still loses sometimes`() {
        // Football's whole appeal. A deterministic engine would make the
        // league table a sorted list of ratings and the simulation pointless.
        val lfc = TeamStrength.rate(liverpool())
        val nor = TeamStrength.rate(norwich())
        val random = Random(7)
        var upsets = 0
        repeat(1000) {
            val r = MatchEngine.play(lfc, nor, random)
            if (r.awayGoals >= r.homeGoals) upsets++
        }
        assertTrue("favourite dropped points in $upsets/1000", upsets in 30..400)
    }

    @Test
    fun `simulated goals average out to the expected goals`() {
        // The Poisson draw must be unbiased, or a season's totals drift away
        // from everything the model was fitted on.
        val random = Random(99)
        val lambda = 1.62
        val total = (1..40000).sumOf { MatchEngine.poisson(lambda, random) }
        val mean = total / 40000.0
        assertTrue("mean draw was $mean against lambda $lambda", abs(mean - lambda) < 0.05)
    }

    @Test
    fun `a whole season of fixtures lands in a believable range`() {
        val lfc = TeamStrength.rate(liverpool())
        val nor = TeamStrength.rate(norwich())
        val random = Random(1)
        var points = 0
        var scored = 0
        var conceded = 0
        // 19 home and 19 away against relegation-standard opposition is not a
        // real fixture list, but it is a fair smoke test of season scale.
        repeat(19) {
            val h = MatchEngine.play(lfc, nor, random)
            points += h.homePoints; scored += h.homeGoals; conceded += h.awayGoals
            val a = MatchEngine.play(nor, lfc, random)
            points += a.awayPoints; scored += a.awayGoals; conceded += a.homeGoals
        }
        assertTrue("points $points", points in 60..114)
        assertTrue("scored $scored", scored in 40..140)
        assertTrue("conceded $conceded", conceded in 5..70)
    }

    @Test
    fun `attack saturates only beyond anything ever observed`() {
        // The fit is trusted across the whole measured range and must not be
        // bent inside it — Manchester City 2019/20 sat at exactly 3.00.
        assertEquals(1.27, TeamStrength.saturate(1.27), 1e-9)
        assertEquals(2.61, TeamStrength.saturate(2.61), 1e-9)
        assertEquals(3.00, TeamStrength.saturate(3.00), 1e-9)
        // Past it the curve bends and can never exceed the asymptote.
        assertTrue(TeamStrength.saturate(3.97) < 3.97)
        // Converges TO the asymptote, so the bound is inclusive; a strict
        // less-than fails on an input large enough to reach it exactly.
        assertTrue(TeamStrength.saturate(50.0) <= SimModel.ATTACK_SATURATION_ASYMPTOTE)
        assertTrue(TeamStrength.saturate(50.0) > SimModel.ATTACK_LINEAR_MAX)
    }
}
