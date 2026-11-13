package com.dreamxi.app.sim.europeancup

import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.mixSeed
import com.dreamxi.app.sim.worldcup.GroupResult
import com.dreamxi.app.sim.worldcup.TiebreakRules
import com.dreamxi.app.sim.worldcup.WcGoal
import com.dreamxi.app.sim.worldcup.WcGroupRow
import com.dreamxi.app.sim.worldcup.WcGroupTable
import com.dreamxi.app.sim.worldcup.WcScore
import com.dreamxi.app.sim.worldcup.WcTournamentSimulator
import kotlin.random.Random

/** A match as it went in this run: real, or played by the engine. */
data class EcPlayedMatch(
    val stage: EcStage,
    val group: String?,
    /** The knockout tie this is a leg of; null in the group stage. */
    val slotId: String?,
    val leg: Int,
    val date: String?,
    val homeId: String,
    val awayId: String,
    /** Totals, extra time included. A real match does not split them: its goals are not stored by minute. */
    val homeGoals: Int,
    val awayGoals: Int,
    val extraTime: Boolean,
    val homePens: Int?,
    val awayPens: Int?,
    /** Scorers: simulated matches only. Real scorers are not loaded. */
    val goals: List<WcGoal>,
    /** False when this is the real result, untouched. */
    val simulated: Boolean,
) {
    fun involves(teamId: String): Boolean = homeId == teamId || awayId == teamId
}

/** A knockout tie, settled. [first] hosted leg 1; a final has one leg. */
data class EcTieOutcome(
    val slotId: String,
    val stage: EcStage,
    val firstId: String,
    val secondId: String,
    val winnerId: String,
    val decidedBy: TieDecision,
    val firstAggregate: Int,
    val secondAggregate: Int,
    val legs: List<EcPlayedMatch>,
) {
    val loserId: String get() = if (winnerId == firstId) secondId else firstId
    val simulated: Boolean get() = legs.any { it.simulated }
}

data class EcTournamentResult(
    val seasonYear: Int,
    val tables: Map<String, List<WcGroupRow>>,
    val matches: List<EcPlayedMatch>,
    val ties: List<EcTieOutcome>,
    val championId: String,
    val userId: String?,
    val replacedId: String?,
    val seed: Long,
) {
    fun matchesOf(teamId: String): List<EcPlayedMatch> = matches.filter { it.involves(teamId) }
}

/**
 * Every club's rating on one scale, so a club with no roster can still play.
 *
 * A club with a lineup is rated from it. One without (Red Star Belgrade) has
 * only a strength figure, so its rating is read off a straight line fitted to
 * the clubs that have both: strength against each of the four rating fields.
 * The fit is over thirty-one clubs and is used for one, which is why it is a
 * line and not anything cleverer.
 */
class EcRatings(entries: List<EcEntry>) {
    val byId: Map<String, TeamRating>
    val field: EcField

    init {
        val rated = entries.filter { it.lineup != null }
        require(rated.size >= 8) { "cannot rate a field from ${rated.size} lineups" }
        fun line(y: (TeamRating) -> Double): (Double) -> Double {
            val xs = rated.map { it.strength }
            val ys = rated.map { y(it.lineup!!.rating) }
            val mx = xs.average()
            val my = ys.average()
            val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
            return { x -> my + slope * (x - mx) }
        }
        val attack = line { it.attack }
        val defence = line { it.defence }
        val attackRaw = line { it.attackRaw }
        val defensive = line { it.defensiveRating }
        byId = entries.associate { e ->
            e.id to (e.lineup?.rating ?: TeamRating(
                attack = attack(e.strength), defence = defence(e.strength),
                attackRaw = attackRaw(e.strength), defensiveRating = defensive(e.strength),
            ))
        }
        field = EcField.of(byId.values)
    }
}

/**
 * Replays a European Cup season with the user's XI in place of one club.
 *
 * Real results stand for every match the user is not in. The user's matches are
 * simulated, his group is re-ranked, and the bracket then follows the real one:
 * a tie the real tournament played between the same two clubs keeps its real
 * result, and any other is played by the engine. With nobody replaced the run
 * reproduces the real season exactly (EuropeanCupReplayTest holds it to that).
 */
object EcTournamentSimulator {

    /** Results and scoresheets on separate streams, for the club engine's reason. */
    private const val EVENT_STREAM_OFFSET = 0x5EED_E0CAL

    fun play(
        data: EcTournamentData,
        user: EcUserTeam?,
        replacedId: String?,
        seed: Long,
        ratings: EcRatings? = null,
    ): EcTournamentResult {
        require((user == null) == (replacedId == null)) { "a user side needs a club to replace, and vice versa" }
        val entries = data.entries.associateBy { it.id }
        val replaced = replacedId?.let { requireNotNull(entries[it]) { "$it is not in ${data.seasonYear}" } }
        if (replaced != null) {
            require(replaced.finishedBottom) { "${replaced.name} did not finish bottom of its group" }
            require(user!!.id !in entries) { "the user's id ${user.id} collides with a real club" }
        }

        val random = Random(mixSeed(seed))
        val events = Random(mixSeed(seed + EVENT_STREAM_OFFSET))
        val userId = user?.id
        // Only needed once a match is simulated: a replay with nobody replaced never rates a side.
        val rated by lazy { ratings ?: EcRatings(data.entries) }
        val field by lazy { rated.field }

        fun resolve(id: String): String = if (id == replacedId) userId!! else id
        fun side(id: String): EcSide =
            EcSide(if (id == userId) user!!.lineup.rating else rated.byId.getValue(id))
        fun scorers(id: String): SquadProfile =
            if (id == userId) user!!.lineup.scorers else entries[id]?.lineup?.scorers ?: SquadProfile.EMPTY

        val played = mutableListOf<EcPlayedMatch>()

        // ------------------------------------------------------ group stage
        for (m in data.matches(EcStage.GROUP).sortedBy { it.date }) {
            val home = resolve(m.homeId)
            val away = resolve(m.awayId)
            played += if (home == userId || away == userId) {
                val score = EcMatchEngine.playGroup(side(home), side(away), field, random)
                playedFrom(EcStage.GROUP, m.group, null, m.leg, m.date, home, away, score,
                    WcTournamentSimulator.simulatedGoals(home, away, score, ::scorers, events))
            } else {
                real(m)
            }
        }

        val userGroup = replaced?.group
        val tables = data.groups.mapValues { (letter, members) ->
            val ids = members.map { resolve(it.id) }
            val results = played.filter { it.group == letter }
                .map { GroupResult(it.homeId, it.awayId, it.homeGoals, it.awayGoals) }
            if (letter == userGroup) {
                WcGroupTable.rank(ids, results, TiebreakRules.HEAD_TO_HEAD_FIRST, random)
            } else {
                // A group the user is not in keeps its real order: real history is never re-drawn.
                val rows = WcGroupTable.rows(ids, results)
                members.sortedBy { it.realPosition }.map { rows.getValue(it.id) }
            }
        }

        // --------------------------------------------------------- knockouts
        val outcomes = mutableMapOf<String, EcTieOutcome>()
        fun source(s: EcSource): String = when (s) {
            is EcSource.Group -> tables.getValue(s.letter)[s.position - 1].teamId
            is EcSource.WinnerOf -> outcomes.getValue(s.slotId).winnerId
        }

        for (slot in EcBrackets.from(data)) {
            val first = source(slot.home)
            val second = source(slot.away)
            val realLegs = data.matches(slot.stage)
                .filter { setOf(it.homeId, it.awayId) == setOf(first, second) }
                .sortedBy { it.leg }
            val outcome = if (realLegs.isNotEmpty()) {
                realTie(slot, realLegs, data.awayGoalsRule)
            } else if (slot.stage == EcStage.FINAL) {
                val score = EcMatchEngine.playFinal(side(first), side(second), field, random)
                val leg = playedFrom(slot.stage, null, slot.id, 1, slot.legDates.firstOrNull(), first, second, score,
                    WcTournamentSimulator.simulatedGoals(first, second, score, ::scorers, events))
                val firstWon = checkNotNull(score.homeWon)
                EcTieOutcome(slot.id, slot.stage, first, second, if (firstWon) first else second,
                    if (score.homeGoals != score.awayGoals) TieDecision.AGGREGATE else TieDecision.PENALTIES,
                    score.homeGoals, score.awayGoals, listOf(leg))
            } else {
                simulatedTie(slot, first, second, data.awayGoalsRule, ::side, ::scorers, field, random, events)
            }
            outcomes[slot.id] = outcome
            played += outcome.legs
        }

        val final = outcomes.values.single { it.stage == EcStage.FINAL }
        return EcTournamentResult(
            seasonYear = data.seasonYear,
            tables = tables,
            matches = played,
            ties = outcomes.values.toList(),
            championId = final.winnerId,
            userId = userId,
            replacedId = replacedId,
            seed = seed,
        )
    }

    private fun simulatedTie(
        slot: EcSlot,
        first: String,
        second: String,
        awayGoalsRule: Boolean,
        side: (String) -> EcSide,
        scorers: (String) -> SquadProfile,
        field: EcField,
        random: Random,
        events: Random,
    ): EcTieOutcome {
        val tie = EcMatchEngine.playTie(side(first), side(second), field, awayGoalsRule, random)
        val legs = listOf(
            playedFrom(slot.stage, null, slot.id, 1, slot.legDates.getOrNull(0), first, second, tie.leg1,
                WcTournamentSimulator.simulatedGoals(first, second, tie.leg1, scorers, events)),
            playedFrom(slot.stage, null, slot.id, 2, slot.legDates.getOrNull(1), second, first, tie.leg2,
                WcTournamentSimulator.simulatedGoals(second, first, tie.leg2, scorers, events)),
        )
        return EcTieOutcome(
            slot.id, slot.stage, first, second, if (tie.winnerIsFirst) first else second, tie.decidedBy,
            tie.firstAggregate, tie.secondAggregate, legs,
        )
    }

    /** A tie the real tournament played, settled by the same rules a simulated one is. */
    private fun realTie(slot: EcSlot, legs: List<EcRealMatch>, awayGoalsRule: Boolean): EcTieOutcome {
        require(legs.size == slot.stage.legs) { "${slot.id} has ${legs.size} real legs" }
        val first = legs.first().homeId
        val second = legs.first().awayId
        val played = legs.map(::real)
        if (legs.size == 1) {
            val m = legs.single()
            val firstWon = if (m.homeGoals != m.awayGoals) m.homeGoals > m.awayGoals else checkNotNull(m.penaltiesHomeWon())
            return EcTieOutcome(
                slot.id, slot.stage, first, second, if (firstWon) first else second,
                if (m.homeGoals != m.awayGoals) TieDecision.AGGREGATE else TieDecision.PENALTIES,
                m.homeGoals, m.awayGoals, played,
            )
        }
        val (l1, l2) = legs
        val decision = EcTieRules.decide(
            firstGoals = l1.homeGoals + l2.awayGoals,
            secondGoals = l1.awayGoals + l2.homeGoals,
            firstAway = l2.awayGoals,
            secondAway = l1.awayGoals,
            awayGoalsRule = awayGoalsRule,
            // Leg 2's home side is the second club, so the first won the shootout if the AWAY side did.
            firstWonShootout = l2.penaltiesHomeWon()?.let { !it },
        )
        return EcTieOutcome(
            slot.id, slot.stage, first, second, if (decision.winnerIsFirst) first else second, decision.decidedBy,
            l1.homeGoals + l2.awayGoals, l1.awayGoals + l2.homeGoals, played,
        )
    }

    private fun EcRealMatch.penaltiesHomeWon(): Boolean? =
        if (wentToPenalties) homePens!! > awayPens!! else null

    private fun real(m: EcRealMatch) = EcPlayedMatch(
        stage = m.stage, group = m.group, slotId = null, leg = m.leg, date = m.date,
        homeId = m.homeId, awayId = m.awayId, homeGoals = m.homeGoals, awayGoals = m.awayGoals,
        extraTime = m.extraTime, homePens = m.homePens, awayPens = m.awayPens,
        goals = emptyList(), simulated = false,
    )

    private fun playedFrom(
        stage: EcStage, group: String?, slotId: String?, leg: Int, date: String?,
        homeId: String, awayId: String, score: WcScore, goals: List<WcGoal>,
    ) = EcPlayedMatch(
        stage = stage, group = group, slotId = slotId, leg = leg, date = date,
        homeId = homeId, awayId = awayId, homeGoals = score.homeGoals, awayGoals = score.awayGoals,
        extraTime = score.extraTime, homePens = score.shootout?.homeScore, awayPens = score.shootout?.awayScore,
        goals = goals, simulated = true,
    )
}
