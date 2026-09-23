package com.dreamxi.app.sim.worldcup

import com.dreamxi.app.sim.GoalMinutes
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.mixSeed
import kotlin.random.Random

const val GROUP_ROUND = "Group stage"
const val FINAL_ROUND = "Final"
const val THIRD_PLACE_ROUND = "Third-place match"

enum class GoalKind { GOAL, PENALTY, OWN_GOAL }

/**
 * A goal. For an own goal, [teamId] is the side it COUNTS for — how every
 * scoresheet lists it — and [scorer] played for the other one.
 */
data class WcGoal(
    val teamId: String,
    val scorer: String,
    val minute: Int,
    val stoppage: Int? = null,
    val kind: GoalKind = GoalKind.GOAL,
)

/**
 * A nation in one real tournament.
 *
 * @param realPosition where it really finished in its group.
 * @param lineup its strongest side, or null when its squad cannot field one —
 *   which only happens to bottom-of-group nations, and a bottom nation is either
 *   the one the user replaces or one that never plays a simulated match.
 */
data class WcEntry(
    val id: String,
    val name: String,
    val group: String,
    val realPosition: Int,
    val finishedBottom: Boolean,
    val isHost: Boolean = false,
    val lineup: NationalLineup? = null,
)

/** A match as it really happened, with its real scorers where they are known. */
data class WcRealMatch(
    val round: String,
    val date: String,
    val homeId: String,
    val awayId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val homePens: Int? = null,
    val awayPens: Int? = null,
    /** Who won the shootout, when there was one. Known even where the score is not. */
    val shootoutWinnerId: String? = null,
    val goals: List<WcGoal> = emptyList(),
)

/** One real World Cup, everything the engine needs to replay it. */
data class WcTournamentData(
    val year: Int,
    val entries: List<WcEntry>,
    val matches: List<WcRealMatch>,
) {
    val rules: TiebreakRules get() = TiebreakRules.forYear(year)
    val bracket: List<BracketSlot> get() = requireNotNull(WcBrackets.byYear[year]) { "no bracket for $year" }
    val groups: Map<String, List<WcEntry>> get() = entries.groupBy { it.group }.toSortedMap()
}

/** The user's side: an id no real nation uses, and his eleven in their roles. */
data class WcUserTeam(val id: String, val name: String, val lineup: NationalLineup)

/** A match as it went in this run — real, or played by the engine. */
data class WcPlayedMatch(
    val round: String,
    val slotId: String?,
    val group: String?,
    val date: String?,
    val homeId: String,
    val awayId: String,
    val score: WcScore,
    val goals: List<WcGoal>,
    /** False when this is the real result, untouched. */
    val simulated: Boolean,
) {
    val winnerId: String? get() = score.homeWon?.let { if (it) homeId else awayId }
    val loserId: String? get() = score.homeWon?.let { if (it) awayId else homeId }
    fun involves(teamId: String): Boolean = homeId == teamId || awayId == teamId
}

data class WcTournamentResult(
    val year: Int,
    val tables: Map<String, List<WcGroupRow>>,
    /** 2026 only: the third-placed teams that went through, best first. */
    val qualifiedThirds: List<String>,
    val matches: List<WcPlayedMatch>,
    val championId: String,
    val userId: String?,
    val replacedId: String?,
    val seed: Long,
) {
    val userMatches: List<WcPlayedMatch>
        get() = userId?.let { id -> matches.filter { it.involves(id) } }.orEmpty()

    /**
     * How far the user's side went: "Champion", or the round he went out in
     * (the group stage included). Null for a replay with nobody replaced.
     */
    val userFinish: String?
        get() {
            val id = userId ?: return null
            if (championId == id) return "Champion"
            val knockout = userMatches.filter { it.round != GROUP_ROUND && it.round != THIRD_PLACE_ROUND }
            return knockout.lastOrNull()?.round ?: GROUP_ROUND
        }

    /** Goals per scorer and side, own goals excluded, most first. */
    fun topScorers(): List<Pair<Pair<String, String>, Int>> =
        matches.flatMap { it.goals }
            .filter { it.kind != GoalKind.OWN_GOAL }
            .groupingBy { it.scorer to it.teamId }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<Pair<String, String>, Int>> { it.second }.thenBy { it.first.first })
}

/**
 * Plays a World Cup the way the club season is played: as a COUNTERFACTUAL.
 *
 * The user's XI takes the place of one nation that finished bottom of its
 * group. Nothing else about history changes on its own — every match his side
 * is not in keeps its real score and real scorers — but his results change his
 * group, and a changed group sends different teams into the knockout bracket.
 *
 * THE CASCADE. Each knockout tie is resolved from the bracket. If the two sides
 * that arrive are the two that really met in that round, the real result
 * stands, shootout and all. If they are not — because the user is one of them,
 * or because his group sent a different nation down that side of the draw — the
 * tie never happened and the engine plays it. So one upset can rewrite a whole
 * branch of the tournament, while the other branch plays out exactly as it did.
 *
 * GROUP TABLES. The user's group is re-ranked under that edition's own
 * tiebreak rules ([TiebreakRules]). Every other group keeps its REAL finishing
 * order — its matches are unchanged, and a real tie settled by fair play (for
 * which there is no card data here) must not be re-decided by drawing lots.
 *
 * 2026's THIRD-PLACED TEAMS. The best eight thirds go through, and which group
 * winner each one meets depends on WHICH eight: that is FIFA's 495-row table,
 * [WcThirdPlaceTable2026]. Thirds exactly level on points, goal difference and
 * goals keep their real order when neither came from the user's group, and are
 * separated by lots when one did.
 *
 * With nobody replaced, the run reproduces the real tournament exactly —
 * WcTournamentReplayTest holds every edition to that.
 */
object WcTournamentSimulator {

    /**
     * Results and scoresheets on separate random streams, for the club engine's
     * reason: changing how a goal's minute is drawn must never move a result.
     */
    private const val EVENT_STREAM_OFFSET = 0x5EED_2026L

    fun play(
        data: WcTournamentData,
        user: WcUserTeam?,
        replacedId: String?,
        seed: Long,
    ): WcTournamentResult {
        require((user == null) == (replacedId == null)) { "a user side needs a nation to replace, and vice versa" }
        val entries = data.entries.associateBy { it.id }
        val replaced = replacedId?.let { requireNotNull(entries[it]) { "$it is not in ${data.year}" } }
        if (replaced != null) {
            require(replaced.finishedBottom) { "${replaced.name} did not finish bottom of its group" }
            require(user!!.id !in entries) { "the user's id ${user.id} collides with a real nation" }
        }

        val random = Random(mixSeed(seed))
        val events = Random(mixSeed(seed + EVENT_STREAM_OFFSET))
        val userId = user?.id

        fun resolve(id: String): String = if (id == replacedId) userId!! else id

        fun side(id: String): WcSide =
            if (id == userId) {
                // The user's XI inherits a host's advantage along with its fixtures
                // and venues — Qatar 2022 finished bottom and can be taken over —
                // exactly as the club mode's XI keeps a relegated club's home games.
                WcSide(user!!.lineup.rating, isHost = replaced!!.isHost)
            } else {
                val entry = entries.getValue(id)
                val lineup = checkNotNull(entry.lineup) {
                    "${entry.name} ${data.year} cannot field an XI but has to play a simulated match"
                }
                WcSide(lineup.rating, isHost = entry.isHost)
            }

        fun scorers(id: String): SquadProfile =
            if (id == userId) user!!.lineup.scorers else entries[id]?.lineup?.scorers ?: SquadProfile.EMPTY

        val played = mutableListOf<WcPlayedMatch>()
        val groupOf = data.entries.associate { it.id to it.group }

        // ------------------------------------------------------ group stage
        for (m in data.matches.filter { it.round == GROUP_ROUND }.sortedBy { it.date }) {
            val home = resolve(m.homeId)
            val away = resolve(m.awayId)
            played += if (home == userId || away == userId) {
                val score = WcMatchEngine.playGroup(side(home), side(away), random)
                WcPlayedMatch(
                    GROUP_ROUND, null, groupOf[m.homeId], m.date, home, away, score,
                    simulatedGoals(home, away, score, ::scorers, events), simulated = true,
                )
            } else {
                real(m, groupOf[m.homeId], null)
            }
        }

        val userGroup = replaced?.group
        val tables = data.groups.mapValues { (letter, members) ->
            val ids = members.map { resolve(it.id) }
            val results = played.filter { it.group == letter }
                .map { GroupResult(it.homeId, it.awayId, it.score.homeGoals, it.score.awayGoals) }
            if (letter == userGroup) {
                WcGroupTable.rank(ids, results, data.rules, random)
            } else {
                val rows = WcGroupTable.rows(ids, results)
                members.sortedBy { it.realPosition }.map { rows.getValue(it.id) }
            }
        }

        // ------------------------------------------ 2026: best third places
        val bracket = data.bracket
        val usesThirds = bracket.any { it.home is SlotSource.ThirdAgainst || it.away is SlotSource.ThirdAgainst }
        var qualifiedThirds = emptyList<String>()
        var thirdAgainst = emptyMap<String, String>()
        if (usesThirds) {
            val realQualified = data.entries
                .filter { it.realPosition == 3 }
                .filter { e -> data.matches.any { it.round != GROUP_ROUND && (it.homeId == e.id || it.awayId == e.id) } }
                .map { it.id }
                .toSet()
            val ranked = rankThirds(tables, userGroup, realQualified, random)
            val qualifying = ranked.take(8)
            qualifiedThirds = qualifying.map { it.second.teamId }
            val key = qualifying.map { it.first }.sorted().joinToString("")
            val assignment = checkNotNull(WcThirdPlaceTable2026.assignments[key]) { "no Annex C row for $key" }
            thirdAgainst = assignment.mapValues { (_, thirdGroup) -> tables.getValue(thirdGroup)[2].teamId }
        }

        // --------------------------------------------------------- knockouts
        val ties = mutableMapOf<String, WcPlayedMatch>()
        fun source(s: SlotSource): String = when (s) {
            is SlotSource.Group -> tables.getValue(s.letter)[s.position - 1].teamId
            is SlotSource.ThirdAgainst -> thirdAgainst.getValue(s.winnerGroup)
            is SlotSource.WinnerOf -> checkNotNull(ties.getValue(s.slotId).winnerId)
            is SlotSource.LoserOf -> checkNotNull(ties.getValue(s.slotId).loserId)
        }
        for (slot in bracket) {
            val home = source(slot.home)
            val away = source(slot.away)
            val realTie = data.matches.firstOrNull {
                it.round == slot.round && setOf(it.homeId, it.awayId) == setOf(home, away)
            }
            val match = if (realTie != null) {
                real(realTie, null, slot.id)
            } else {
                val score = WcMatchEngine.playKnockout(side(home), side(away), random)
                WcPlayedMatch(
                    slot.round, slot.id, null, null, home, away, score,
                    simulatedGoals(home, away, score, ::scorers, events), simulated = true,
                )
            }
            ties[slot.id] = match
            played += match
        }

        val final = checkNotNull(ties.values.firstOrNull { it.round == FINAL_ROUND }) { "the bracket has no final" }
        return WcTournamentResult(
            year = data.year,
            tables = tables,
            qualifiedThirds = qualifiedThirds,
            matches = played,
            championId = checkNotNull(final.winnerId),
            userId = userId,
            replacedId = replacedId,
            seed = seed,
        )
    }

    /** (group letter, third-placed row), best first. */
    private fun rankThirds(
        tables: Map<String, List<WcGroupRow>>,
        userGroup: String?,
        realQualified: Set<String>,
        random: Random,
    ): List<Pair<String, WcGroupRow>> {
        val thirds = tables.map { (letter, rows) -> letter to rows[2] }
        return thirds
            .groupBy { (_, r) -> Triple(r.points, r.goalDifference, r.goalsFor) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Triple<Int, Int, Int>, List<Pair<String, WcGroupRow>>>> { it.key.first }
                    .thenByDescending { it.key.second }
                    .thenByDescending { it.key.third },
            )
            .flatMap { (_, tied) ->
                when {
                    tied.size == 1 -> tied
                    tied.any { it.first == userGroup } -> tied.sortedBy { it.first }.shuffled(random)
                    else -> tied.sortedWith(
                        compareByDescending<Pair<String, WcGroupRow>> { it.second.teamId in realQualified }
                            .thenBy { it.first },
                    )
                }
            }
    }

    private fun real(m: WcRealMatch, group: String?, slotId: String?): WcPlayedMatch {
        val homeExtra = m.goals.count { it.teamId == m.homeId && it.minute > 90 }
        val awayExtra = m.goals.count { it.teamId == m.awayId && it.minute > 90 }
        val shootout = m.shootoutWinnerId?.let { winner ->
            Shootout(homeWon = winner == m.homeId, homeScore = m.homePens, awayScore = m.awayPens)
        }
        check(m.round == GROUP_ROUND || m.homeGoals != m.awayGoals || shootout != null) {
            "${m.round} ${m.homeId} v ${m.awayId} ended level with no shootout winner"
        }
        val score = WcScore(
            homeRegular = m.homeGoals - homeExtra,
            awayRegular = m.awayGoals - awayExtra,
            homeExtra = homeExtra,
            awayExtra = awayExtra,
            extraTime = homeExtra + awayExtra > 0 || shootout != null,
            shootout = shootout,
            homeExpected = m.homeGoals.toDouble(),
            awayExpected = m.awayGoals.toDouble(),
        )
        return WcPlayedMatch(m.round, slotId, group, m.date, m.homeId, m.awayId, score, m.goals, simulated = false)
    }

    /**
     * Who scored in a match the engine played, and when.
     *
     * Weighted by each man's expected goals in the role he is playing — the
     * club engine's attribution weights ([SquadProfile.fromXi]). No season
     * budget: a tournament is seven matches, not thirty-eight, and there is no
     * real total to deal out.
     *
     * Normal-time minutes come from [GoalMinutes], which was measured on the
     * real goals of these very tournaments — so a simulated match leans late at
     * the rate the editions around it do, stoppage-time winners included. Extra
     * time stays a flat draw across 91-120: only 28 real goals were scored
     * there, which is too few to fit a shape to.
     *
     * Simulated goals are not split into penalties and own goals. The real
     * shares exist (8.0% and 3.0% of the 1,112 real goals), but the own goal
     * needs an opponent to credit it to, and nothing measured says which one.
     */
    private fun simulatedGoals(
        homeId: String,
        awayId: String,
        score: WcScore,
        scorers: (String) -> SquadProfile,
        random: Random,
    ): List<WcGoal> {
        // One list of moments for the whole match, both sides together, so two
        // goals never land on the same minute of the same game.
        val taken = mutableListOf<Int>()

        fun goals(teamId: String, count: Int, extraTime: Boolean): List<WcGoal> {
            if (count == 0) return emptyList()
            val players = scorers(teamId).players
            val weights = players.map { it.goals.coerceAtLeast(0.0) }
            return List(count) {
                val name = if (players.isEmpty()) "Unknown" else players[pick(weights, random)].name
                if (extraTime) {
                    // Flat, and kept clear of the goals already scored. 28 real
                    // extra-time goals is too few to fit a shape to.
                    var minute = random.nextInt(91, 121)
                    repeat(GoalMinutes.MIN_SEPARATION * 8) {
                        if (taken.none { t -> kotlin.math.abs(minute - t) < GoalMinutes.MIN_SEPARATION }) {
                            return@repeat
                        }
                        minute = random.nextInt(91, 121)
                    }
                    taken += minute
                    WcGoal(teamId, name, minute)
                } else {
                    val moment = GoalMinutes.draw(random, taken = taken)
                    taken += moment.played
                    WcGoal(teamId, name, moment.minute, moment.stoppage)
                }
            }
        }
        return (
            goals(homeId, score.homeRegular, extraTime = false) +
                goals(homeId, score.homeExtra, extraTime = true) +
                goals(awayId, score.awayRegular, extraTime = false) +
                goals(awayId, score.awayExtra, extraTime = true)
            ).sortedBy { it.minute + (it.stoppage ?: 0) }
    }

    private fun pick(weights: List<Double>, random: Random): Int {
        val total = weights.sum()
        if (total <= 0.0) return random.nextInt(weights.size)
        var roll = random.nextDouble() * total
        for ((i, w) in weights.withIndex()) {
            roll -= w
            if (roll < 0) return i
        }
        return weights.lastIndex
    }
}
