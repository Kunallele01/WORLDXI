package com.dreamxi.app

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.GoalKind
import com.dreamxi.app.sim.worldcup.NationalLineup
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcEntry
import com.dreamxi.app.sim.worldcup.WcGoal
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcRealMatch
import com.dreamxi.app.sim.worldcup.WcSlot
import com.dreamxi.app.sim.worldcup.WcTournamentData

/**
 * Every real 2006-2026 World Cup, rebuilt from the fixtures exported by
 * etl/export_wc_engine_data.py: the same squads, positions, results and real
 * scorers the database holds.
 *
 * Built once and shared, because picking the best eleven for 208 nations over
 * nineteen formations is the slow part of every World Cup test.
 */
object WcFixtures {

    val years = listOf(2006, 2010, 2014, 2018, 2022, 2026)

    private val GRID_ROLES = listOf("CB", "FB", "DM", "CM", "CAM", "Winger", "ST")

    /** Each formation's eleven roles, keeper first — what the app will pass in. */
    val formations: List<List<WcSlot>> = Formations.map { f -> f.slots.map { WcSlot(it.role, it.side, it.isWingBack) } }

    private fun tsv(name: String): List<Map<String, String>> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("wc/$name")) {
            "wc/$name is missing — run etl/export_wc_engine_data.py"
        }.bufferedReader(Charsets.UTF_8).readText()
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.first().split('\t')
        return lines.drop(1).map { line ->
            val cells = line.split('\t')
            header.indices.associate { header[it] to cells.getOrElse(it) { "" } }
        }
    }

    val squads: Map<Pair<Int, String>, List<WcPlayer>> by lazy {
        tsv("players.tsv").groupBy({ it.getValue("year").toInt() to it.getValue("team") }) { row ->
            WcPlayer(
                id = row.getValue("sofifa_id"),
                name = row.getValue("name"),
                rating = row.getValue("rating").toDouble(),
                positions = row.getValue("positions").split(';').filter { it.isNotBlank() },
                grid = GRID_ROLES.associateWith { row.getValue("grid_${it.lowercase()}") }
                    .takeIf { cells -> cells.values.all { it.isNotBlank() } }
                    ?.mapValues { it.value.toInt() },
                gridEdition = row.getValue("grid_edition").ifBlank { null },
            )
        }
    }

    val lineups: Map<Pair<Int, String>, NationalLineup?> by lazy {
        entryRows.associate { row ->
            val key = row.getValue("year").toInt() to row.getValue("team")
            key to NationalSide.bestLineup(squads[key].orEmpty(), formations)
        }
    }

    private val entryRows by lazy { tsv("entries.tsv") }

    val tournaments: Map<Int, WcTournamentData> by lazy {
        val goals = tsv("goals.tsv").groupBy {
            listOf(it.getValue("year"), it.getValue("date"), it.getValue("home"), it.getValue("away"))
        }
        val matches = tsv("matches.tsv")
        years.associateWith { year ->
            val entries = entryRows.filter { it.getValue("year").toInt() == year }.map { row ->
                val team = row.getValue("team")
                WcEntry(
                    id = team,
                    name = team,
                    group = row.getValue("letter"),
                    realPosition = row.getValue("position").toInt(),
                    finishedBottom = row.getValue("bottom") == "1",
                    isHost = row.getValue("host") == "1",
                    lineup = lineups[year to team],
                )
            }
            val real = matches.filter { it.getValue("year").toInt() == year }.map { row ->
                val key = listOf(row.getValue("year"), row.getValue("date"), row.getValue("home"), row.getValue("away"))
                WcRealMatch(
                    round = row.getValue("round"),
                    date = row.getValue("date"),
                    homeId = row.getValue("home"),
                    awayId = row.getValue("away"),
                    homeGoals = row.getValue("home_goals").toInt(),
                    awayGoals = row.getValue("away_goals").toInt(),
                    homePens = row.getValue("home_pens").toIntOrNull(),
                    awayPens = row.getValue("away_pens").toIntOrNull(),
                    shootoutWinnerId = row.getValue("shootout_winner").ifBlank { null },
                    goals = goals[key].orEmpty().map { g ->
                        WcGoal(
                            teamId = g.getValue("team"),
                            scorer = g.getValue("scorer"),
                            minute = g.getValue("minute").toInt(),
                            stoppage = g.getValue("stoppage").toIntOrNull(),
                            kind = when (g.getValue("kind")) {
                                "penalty" -> GoalKind.PENALTY
                                "own_goal" -> GoalKind.OWN_GOAL
                                else -> GoalKind.GOAL
                            },
                        )
                    },
                )
            }
            WcTournamentData(year, entries, real)
        }
    }

    /** The real first-round shootout winner or the side with more goals. */
    fun realWinner(m: WcRealMatch): String? = when {
        m.homeGoals > m.awayGoals -> m.homeId
        m.awayGoals > m.homeGoals -> m.awayId
        m.homePens != null && m.awayPens != null -> if (m.homePens > m.awayPens) m.homeId else m.awayId
        else -> null
    }

    fun isKnockout(m: WcRealMatch): Boolean = m.round != GROUP_ROUND
}
