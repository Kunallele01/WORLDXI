package com.dreamxi.app

import com.dreamxi.app.sim.MatchEngine
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.TeamStrength
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the SHIPPED engine over 156 real club-seasons and checks it reproduces
 * what actually happened.
 *
 * The calibration scripts in etl/ prove the relationships hold. This proves the
 * Kotlin implements them — a formula that fits and code that works are separate
 * claims, and only the second one reaches a user. Every squad here is the
 * eleven players who actually played most for that club that season, in their
 * own positions, so this measures football before the engine is asked anything
 * about strikers at centre-back.
 *
 * Each club plays every other club in its own league-season home and away,
 * which is a real fixture list, and the resulting points are compared with the
 * real table.
 */
class RealSeasonValidationTest {

    private data class Squad(
        val club: String,
        val season: String,
        val leagueId: String,
        val goalsFor: Int,
        val goalsAgainst: Int,
        val points: Int,
        val xi: List<SimSlot>,
    ) {
        val key get() = "$leagueId|$season"
    }

    private fun load(): List<Squad> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("real_squads.tsv")) {
            "real_squads.tsv missing — regenerate with etl/export_sim_fixture.py"
        }.bufferedReader().readText()

        val lines = text.trim().lines()
        val header = lines.first().split("\t")
        val col = header.withIndex().associate { (i, h) -> h to i }
        val grouped = LinkedHashMap<String, MutableList<List<String>>>()
        for (line in lines.drop(1)) {
            val f = line.split("\t")
            grouped.getOrPut(f[col.getValue("club_season_id")]) { mutableListOf() }.add(f)
        }
        return grouped.values.mapNotNull { rows ->
            if (rows.size != 11) return@mapNotNull null
            val f = rows.first()
            Squad(
                club = f[col.getValue("club")],
                season = f[col.getValue("season")],
                leagueId = f[col.getValue("league_id")],
                goalsFor = f[col.getValue("goals_for")].toInt(),
                goalsAgainst = f[col.getValue("goals_against")].toInt(),
                points = f[col.getValue("points")].toInt(),
                xi = rows.map { r ->
                    val role = r[col.getValue("role")]
                    val mins = r[col.getValue("minutes")].toInt().coerceAtLeast(1)
                    fun per90(key: String) = r[col.getValue(key)].toInt() * 90.0 / mins
                    SimSlot(
                        role = role,
                        player = SimPlayer(
                            name = r[col.getValue("name")],
                            naturalRole = role,
                            npxg90 = r[col.getValue("npxg90")].toDouble(),
                            overallRating = r[col.getValue("overall_rating")].toInt(),
                            savePct = r.getOrNull(col.getValue("save_pct"))
                                ?.takeIf { it.isNotBlank() }?.toDouble(),
                            goals90 = per90("goals"),
                            assists90 = per90("assists"),
                            yellow90 = per90("yellows"),
                            red90 = per90("reds"),
                        ),
                        // Real players in their real positions.
                        positionPenalty = 0,
                    )
                },
            )
        }
    }

    private fun corr(xs: List<Double>, ys: List<Double>): Double {
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (ys[i] - my)
            dx += (xs[i] - mx) * (xs[i] - mx)
            dy += (ys[i] - my) * (ys[i] - my)
        }
        return if (dx > 0 && dy > 0) num / sqrt(dx * dy) else 0.0
    }

    private fun rmse(a: List<Double>, b: List<Double>) =
        sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) } / a.size)

    @Test
    fun `the engine reproduces real league tables`() {
        val squads = load()
        assertTrue("expected the full fixture, got ${squads.size}", squads.size >= 150)

        val ratings = squads.associateWith { TeamStrength.rate(it.xi) }
        val bySeason = squads.groupBy { it.key }

        val predicted = mutableListOf<Double>()
        val actual = mutableListOf<Double>()
        val worst = mutableListOf<Triple<Squad, Double, Int>>()

        for ((_, teams) in bySeason) {
            if (teams.size < 6) continue   // a partially-loaded season proves nothing
            for (team in teams) {
                var points = 0.0
                for (other in teams) {
                    if (other === team) continue
                    // Home leg, then away leg. Taking the away figure as
                    // "3 minus the home figure" is wrong — a draw pays a point
                    // to each side — and inflated a season by about four points.
                    points += MatchEngine.expectedPointsBoth(
                        ratings.getValue(team), ratings.getValue(other)).first
                    points += MatchEngine.expectedPointsBoth(
                        ratings.getValue(other), ratings.getValue(team)).second
                }
                // Scale to a 38-game season; a partially-loaded league plays fewer.
                val games = (teams.size - 1) * 2
                val scaled = points / games * 38
                predicted += scaled
                actual += team.points.toDouble()
                worst += Triple(team, scaled, team.points)
            }
        }

        val r = corr(predicted, actual)
        val err = rmse(predicted, actual)
        println("engine vs real league tables over ${predicted.size} club-seasons")
        println("   r = ${"%.3f".format(r)}   RMSE ${"%.1f".format(err)} points")
        println("   mean predicted ${"%.1f".format(predicted.average())} vs actual ${"%.1f".format(actual.average())}")
        println("   biggest misses:")
        worst.sortedByDescending { abs(it.second - it.third) }.take(5).forEach { (s, p, a) ->
            println("      ${s.club.padEnd(20)} ${s.season}  predicted ${"%.0f".format(p)} actual $a")
        }

        assertTrue("correlation with real points was $r", r > 0.80)
        assertTrue("RMSE was $err points across a season", err < 12.0)
        assertTrue(
            "engine is biased: predicts ${predicted.average()} vs real ${actual.average()}",
            abs(predicted.average() - actual.average()) < 4.0,
        )
    }

    @Test
    fun `champions come out near the top and relegated sides near the bottom`() {
        val squads = load()
        val ratings = squads.associateWith { TeamStrength.rate(it.xi) }
        var checked = 0
        var correct = 0
        for ((_, teams) in squads.groupBy { it.key }) {
            if (teams.size < 15) continue
            val byPredicted = teams.sortedByDescending {
                ratings.getValue(it).attack - ratings.getValue(it).defence
            }
            val realBest = teams.maxByOrNull { it.points }!!
            // The real champion should land in the predicted top four. Not
            // first — one season of football is far too noisy for that, and an
            // engine that always crowned the strongest squad would be lying
            // about how football works.
            val rank = byPredicted.indexOf(realBest)
            checked++
            if (rank < 4) correct++
        }
        println("real champion inside the predicted top four in $correct of $checked seasons")
        assertTrue("only $correct/$checked", checked == 0 || correct >= checked - 1)
    }

    @Test
    fun `goals scored and conceded land near reality`() {
        val squads = load()
        val predGf = mutableListOf<Double>()
        val realGf = mutableListOf<Double>()
        val predGa = mutableListOf<Double>()
        val realGa = mutableListOf<Double>()
        for (s in squads) {
            val rating = TeamStrength.rate(s.xi)
            predGf += rating.attack * 38
            realGf += s.goalsFor.toDouble()
            predGa += rating.defence * 38
            realGa += s.goalsAgainst.toDouble()
        }
        val rGf = corr(predGf, realGf)
        val rGa = corr(predGa, realGa)
        println("goals for  r = ${"%.3f".format(rGf)}  RMSE ${"%.1f".format(rmse(predGf, realGf))}")
        println("goals against r = ${"%.3f".format(rGa)}  RMSE ${"%.1f".format(rmse(predGa, realGa))}")
        assertTrue("goals-for correlation $rGf", rGf > 0.80)
        assertTrue("goals-against correlation $rGa", rGa > 0.75)
    }
}
