package com.dreamxi.app.sim

/** A player's season, as the stats screen shows it. */
data class PlayerSeasonLine(
    val player: String,
    val teamId: String,
    val teamName: String,
    val isUserTeam: Boolean,
    val goals: Int = 0,
    val assists: Int = 0,
    val yellows: Int = 0,
    val reds: Int = 0,
) {
    /** Goals plus assists — the "involvements" number every broadcast uses. */
    val involvements: Int get() = goals + assists
}

/** A team's disciplinary and defensive record over the season. */
data class TeamSeasonLine(
    val teamId: String,
    val teamName: String,
    val isUserTeam: Boolean,
    val cleanSheets: Int = 0,
    val yellows: Int = 0,
    val reds: Int = 0,
)

/** The end-of-season numbers. */
data class SeasonStats(
    val topScorers: List<PlayerSeasonLine>,
    val topAssists: List<PlayerSeasonLine>,
    val topInvolvements: List<PlayerSeasonLine>,
    val mostBooked: List<PlayerSeasonLine>,
    val cleanSheets: List<TeamSeasonLine>,
    /**
     * The user's own XI, ranked among itself.
     *
     * Kept as separate lists rather than filtered out of the league ones at the
     * point of display, which is what the screen used to do and is why it only
     * ever showed a single name: the league lists are capped at the top ten, so
     * filtering them to one club leaves however many of that club's players
     * happened to crack the league's top ten. Usually one.
     */
    val userScorers: List<PlayerSeasonLine>,
    val userAssists: List<PlayerSeasonLine>,
    val userInvolvements: List<PlayerSeasonLine>,
    val userBooked: List<PlayerSeasonLine>,
    val totalGoals: Int,
    val totalYellows: Int,
    val totalReds: Int,
) {
    val goldenBoot: PlayerSeasonLine? get() = topScorers.firstOrNull()
    val playmaker: PlayerSeasonLine? get() = topAssists.firstOrNull()
}

/**
 * Rolls a played season up into the leaderboards.
 *
 * Ties are broken so the ordering is stable and defensible rather than
 * dependent on which fixture happened to be processed first: a scorer with
 * more goals leads, then more assists, then alphabetically. Without the last
 * step two players on the same numbers could swap places between recompositions
 * and the list would appear to shuffle itself while the user watched.
 */
object SeasonStatsBuilder {

    fun build(fixtures: List<SimFixture>, limit: Int = 10): SeasonStats {
        val teamNames = fixtures
            .flatMap { listOf(it.home, it.away) }
            .distinctBy { it.id }
            .associateBy { it.id }

        val players = mutableMapOf<Pair<String, String>, PlayerSeasonLine>()

        fun line(teamId: String, name: String): PlayerSeasonLine {
            val team = teamNames[teamId]
            return players.getOrPut(teamId to name) {
                PlayerSeasonLine(
                    player = name,
                    teamId = teamId,
                    teamName = team?.name.orEmpty(),
                    isUserTeam = team?.isUserTeam == true,
                )
            }
        }

        for (f in fixtures) {
            for (g in f.events.goals) {
                val scorer = line(g.teamId, g.scorer)
                players[g.teamId to g.scorer] = scorer.copy(goals = scorer.goals + 1)
                g.assist?.let { a ->
                    val assister = line(g.teamId, a)
                    players[g.teamId to a] = assister.copy(assists = assister.assists + 1)
                }
            }
            for (c in f.events.cards) {
                val p = line(c.teamId, c.player)
                players[c.teamId to c.player] =
                    if (c.isRed) p.copy(reds = p.reds + 1) else p.copy(yellows = p.yellows + 1)
            }
        }

        val all = players.values.toList()

        // Shared so a player cannot rank differently in the league list and in
        // his own club's list.
        val byGoals = compareByDescending<PlayerSeasonLine> { it.goals }
            .thenByDescending { it.assists }.thenBy { it.player }
        val byAssists = compareByDescending<PlayerSeasonLine> { it.assists }
            .thenByDescending { it.goals }.thenBy { it.player }
        val byInvolvements = compareByDescending<PlayerSeasonLine> { it.involvements }
            .thenByDescending { it.goals }.thenBy { it.player }
        val byCards = compareByDescending<PlayerSeasonLine> { it.reds * 3 + it.yellows }
            .thenByDescending { it.reds }.thenBy { it.player }

        val teams = teamNames.values.map { team ->
            val played = fixtures.filter { it.home.id == team.id || it.away.id == team.id }
            TeamSeasonLine(
                teamId = team.id,
                teamName = team.name,
                isUserTeam = team.isUserTeam,
                cleanSheets = played.count { it.goalsAgainst(team) == 0 },
                yellows = played.sumOf { f -> f.events.cardsFor(team.id).count { !it.isRed } },
                reds = played.sumOf { f -> f.events.cardsFor(team.id).count { it.isRed } },
            )
        }

        return SeasonStats(
            topScorers = all
                .filter { it.goals > 0 }
                .sortedWith(byGoals)
                .take(limit),
            topAssists = all
                .filter { it.assists > 0 }
                .sortedWith(byAssists)
                .take(limit),
            topInvolvements = all
                .filter { it.involvements > 0 }
                .sortedWith(byInvolvements)
                .take(limit),
            mostBooked = all
                .filter { it.yellows + it.reds > 0 }
                .sortedWith(byCards)
                .take(limit),
            cleanSheets = teams
                .sortedWith(
                    compareByDescending<TeamSeasonLine> { it.cleanSheets }.thenBy { it.teamName },
                )
                .take(limit),
            userScorers = all.filter { it.isUserTeam && it.goals > 0 }
                .sortedWith(byGoals).take(limit),
            userAssists = all.filter { it.isUserTeam && it.assists > 0 }
                .sortedWith(byAssists).take(limit),
            userInvolvements = all.filter { it.isUserTeam && it.involvements > 0 }
                .sortedWith(byInvolvements).take(limit),
            userBooked = all.filter { it.isUserTeam && it.yellows + it.reds > 0 }
                .sortedWith(byCards).take(limit),
            totalGoals = fixtures.sumOf { it.result.homeGoals + it.result.awayGoals },
            totalYellows = fixtures.sumOf { f -> f.events.cards.count { !it.isRed } },
            totalReds = fixtures.sumOf { f -> f.events.cards.count { it.isRed } },
        )
    }
}
