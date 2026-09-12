package com.dreamxi.app.navigation

import android.net.Uri

/**
 * Screen inventory per PROJECT_SPEC_v2.md §10.6. One route object per screen;
 * routes that carry nav arguments expose a `route` pattern for the NavHost and
 * a builder for callers.
 */
sealed class DreamXiDestination(val route: String) {
    /** Temporary: living design-system reference (§13 Milestone 4). Remove once approved. */
    data object DesignSystem : DreamXiDestination("design_system")
    /** The opening: a ball struck into the net, then the name. */
    data object Splash : DreamXiDestination("splash")

    data object Onboarding : DreamXiDestination("onboarding")
    data object Home : DreamXiDestination("home")
    data object Setup : DreamXiDestination("setup")

    /**
     * The draft carries the league chosen at Setup, plus that run's change-spin
     * allowance.
     *
     * The allowance travels WITH the run rather than being read from settings
     * at draft time, mirroring `draft_runs.rerolls_allowed` in the database:
     * changing the difficulty setting later must not retroactively rewrite a
     * run that is already under way.
     */
    data object Draft : DreamXiDestination("draft/{leagueId}/{leagueName}/{formation}/{rerolls}") {
        const val ARG_LEAGUE_ID = "leagueId"
        const val ARG_LEAGUE_NAME = "leagueName"
        const val ARG_FORMATION = "formation"
        const val ARG_REROLLS = "rerolls"

        fun build(leagueId: Long, leagueName: String, formationId: String, rerolls: Int): String =
            "draft/$leagueId/${Uri.encode(leagueName)}/${Uri.encode(formationId)}/$rerolls"
    }

    /** Free Mode carries the same three things a draft does, minus the rerolls. */
    data object FreeMode : DreamXiDestination("free/{leagueId}/{leagueName}/{formation}") {
        fun build(leagueId: Long, leagueName: String, formationId: String): String =
            "free/$leagueId/${Uri.encode(leagueName)}/${Uri.encode(formationId)}"
    }

    data object Finalize : DreamXiDestination("finalize")

    /**
     * The season. Carries no arguments: the run it plays lives in
     * [com.dreamxi.app.data.run.ActiveRun], because an XI is far too big to
     * push through a nav route and will be read from the database once runs
     * are persisted.
     */
    data object Season : DreamXiDestination("season")
    data object Simulating : DreamXiDestination("simulating")
    data object Results : DreamXiDestination("results")
    data object History : DreamXiDestination("history")
    data object Profile : DreamXiDestination("profile")
}
