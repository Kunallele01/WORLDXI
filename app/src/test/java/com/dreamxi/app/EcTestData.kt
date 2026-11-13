package com.dreamxi.app

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.sim.europeancup.EcEntry
import com.dreamxi.app.sim.europeancup.EcRealMatch
import com.dreamxi.app.sim.europeancup.EcStage
import com.dreamxi.app.sim.europeancup.EcTournamentData
import com.dreamxi.app.sim.worldcup.NationalLineup
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcSlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A whole European Cup season for the engine's tests, built from the ETL's own
 * output (the same files the loader writes to Supabase).
 *
 * The fixtures and strengths are committed, so the replay tests always run. The
 * rosters are EA's ratings and are NOT in git (see etl/ec_squads.py), so anything
 * that needs a lineup checks [lineupsAvailable] and quietly skips on a fresh clone.
 *
 * Club names are the engine ids here, which keeps failures readable.
 */
object EcTestData {
    private val etl = File("../etl")
    private val matchesFile = File(etl, "wikipedia/ec_matches_2023.json")
    private val strengthFile = File(etl, "ec/ec_strength_2023.tsv")
    private val playersFile = File(etl, "ec/ec_players_2023.tsv")

    val fixturesAvailable: Boolean get() = matchesFile.isFile && strengthFile.isFile
    val lineupsAvailable: Boolean get() = fixturesAvailable && playersFile.isFile

    private fun tsv(file: File): List<Map<String, String>> {
        val lines = file.readLines(Charsets.UTF_8)
        val header = lines.first().split("\t")
        return lines.drop(1).filter { it.isNotBlank() }.map { header.zip(it.split("\t")).toMap() }
    }

    /** Lineups per club, from the local roster file; empty when it is absent. */
    private fun lineups(): Map<String, NationalLineup> {
        if (!playersFile.isFile) return emptyMap()
        val formations = Formations.map { f -> f.slots.map { WcSlot(it.role, it.side, it.isWingBack) } }
        return tsv(playersFile).groupBy { it["club"]!! }.mapValues { (_, rows) ->
            val squad = rows.map { r ->
                val grid = listOf("cb" to "CB", "fb" to "FB", "dm" to "DM", "cm" to "CM", "cam" to "CAM", "winger" to "Winger", "st" to "ST")
                    .map { (col, role) -> role to r["grid_$col"].orEmpty() }
                    .takeIf { cells -> cells.none { it.second.isEmpty() } }
                    ?.associate { (role, v) -> role to v.toInt() }
                WcPlayer(
                    id = r["sofifa_id"]!!, name = r["name"]!!, rating = r["rating"]!!.toDouble(),
                    positions = r["positions"]!!.split(";"), grid = grid, gridEdition = if (grid != null) "24" else null,
                )
            }
            checkNotNull(NationalSide.bestLineup(squad, formations)) { "no lineup for ${rows.first()["club"]}" }
        }
    }

    /** The best eleven out of every rostered player in the season, as a drafted all-star XI would be. */
    fun allStarLineup(): NationalLineup {
        val formations = Formations.map { f -> f.slots.map { WcSlot(it.role, it.side, it.isWingBack) } }
        val squad = tsv(playersFile).map { r ->
            val grid = listOf("cb" to "CB", "fb" to "FB", "dm" to "DM", "cm" to "CM", "cam" to "CAM", "winger" to "Winger", "st" to "ST")
                .map { (col, role) -> role to r["grid_$col"].orEmpty() }
                .takeIf { cells -> cells.none { it.second.isEmpty() } }
                ?.associate { (role, v) -> role to v.toInt() }
            WcPlayer(
                id = r["sofifa_id"]!!, name = r["name"]!!, rating = r["rating"]!!.toDouble(),
                positions = r["positions"]!!.split(";"), grid = grid, gridEdition = if (grid != null) "24" else null,
            )
        }
        return checkNotNull(NationalSide.bestLineup(squad, formations))
    }

    /** The real 2023/24 season. Lineups are attached where the roster file exists. */
    fun season(withLineups: Boolean = true): EcTournamentData {
        val root = Json.parseToJsonElement(matchesFile.readText(Charsets.UTF_8)).jsonObject
        val matches = root.getValue("matches").jsonArray.map { it.jsonObject.toMatch() }
        val strength = tsv(strengthFile).associate { it["club"]!! to it }
        val lineups = if (withLineups) lineups() else emptyMap()

        val reached = matches.flatMap { listOf(it.homeId to it.stage, it.awayId to it.stage) }
            .groupBy({ it.first }, { it.second }).mapValues { (_, s) -> s.maxBy { it.ordinal } }
        val finalMatch = matches.single { it.stage == EcStage.FINAL }
        val champion = if (finalMatch.homeGoals > finalMatch.awayGoals) finalMatch.homeId else finalMatch.awayId

        val entries = root.getValue("group_tables").jsonObject.flatMap { (group, rows) ->
            rows.jsonArray.mapIndexed { i, row ->
                val club = row.jsonArray[0].jsonPrimitive.content
                val s = strength.getValue(club)
                EcEntry(
                    id = club, name = club, group = group, realPosition = i + 1, finishedBottom = i == 3,
                    strength = s["strength"]!!.toDouble(), strengthEstimated = s["source"] != "squad_top14",
                    reachedStage = reached.getValue(club), champion = club == champion, lineup = lineups[club],
                )
            }
        }
        return EcTournamentData(2023, entries, matches)
    }

    private fun JsonObject.toMatch(): EcRealMatch {
        fun str(k: String) = this[k]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        val pens = str("shootout")?.split("-")?.map { it.toInt() }
        return EcRealMatch(
            stage = EcStage.fromCode(str("stage")!!), group = str("group"), leg = getValue("leg").jsonPrimitive.int,
            date = str("date")!!, homeId = str("home")!!, awayId = str("away")!!,
            homeGoals = getValue("home_goals").jsonPrimitive.int, awayGoals = getValue("away_goals").jsonPrimitive.int,
            extraTime = (this["extra_time"] as? JsonPrimitive)?.content == "true",
            homePens = pens?.get(0), awayPens = pens?.get(1), venue = str("venue"),
        )
    }
}
