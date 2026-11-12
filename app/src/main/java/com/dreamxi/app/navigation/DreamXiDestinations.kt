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

    /**
     * The way in: sign in, create an account, or play as a guest.
     *
     * [ARG_FORCE] is for the player who already has a session and wants this
     * screen anyway — from his profile, to sign into an account he made
     * elsewhere. Without it the screen recognises his session and moves on,
     * which is right after the intro and useless when he asked for it.
     */
    data object Auth : DreamXiDestination("auth?force={force}") {
        const val ARG_FORCE = "force"
        fun build(force: Boolean = false): String = "auth?force=$force"
    }

    /**
     * How the game works, in four swipes.
     *
     * Shown once after the front door, and reopenable from the start screen.
     * [ARG_REPLAY] is true for the second kind: there is a screen to go back to,
     * where the first time the player is on their way to the start screen.
     */
    data object Onboarding : DreamXiDestination("onboarding?replay={replay}") {
        const val ARG_REPLAY = "replay"
        fun build(replay: Boolean = false): String = "onboarding?replay=$replay"
    }
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
    data object Draft : DreamXiDestination("draft/{leagueId}/{leagueName}/{formation}/{rerolls}?resume={resume}") {
        const val ARG_LEAGUE_ID = "leagueId"
        const val ARG_LEAGUE_NAME = "leagueName"
        const val ARG_FORMATION = "formation"
        const val ARG_REROLLS = "rerolls"

        /** True to pick a saved draft back up instead of starting a new one. */
        const val ARG_RESUME = "resume"

        fun build(
            leagueId: Long,
            leagueName: String,
            formationId: String,
            rerolls: Int,
            resume: Boolean = false,
        ): String =
            "draft/$leagueId/${Uri.encode(leagueName)}/${Uri.encode(formationId)}/$rerolls?resume=$resume"
    }

    /** Free Mode carries the same three things a draft does, minus the rerolls. */
    data object FreeMode : DreamXiDestination("free/{leagueId}/{leagueName}/{formation}") {
        fun build(leagueId: Long, leagueName: String, formationId: String): String =
            "free/$leagueId/${Uri.encode(leagueName)}/${Uri.encode(formationId)}"
    }

    /**
     * The World Cup draft carries the formation and change spins chosen at
     * Setup; there is no league, because the pool is every nation at every
     * World Cup.
     */
    data object WorldCupDraft : DreamXiDestination("wc_draft/{formation}/{rerolls}") {
        const val ARG_FORMATION = "formation"
        const val ARG_REROLLS = "rerolls"

        fun build(formationId: String, rerolls: Int): String =
            "wc_draft/${Uri.encode(formationId)}/$rerolls"
    }

    /** World Cup Free Mode carries only the formation: no spins, so no change spins. */
    data object WorldCupFreeMode : DreamXiDestination("wc_free/{formation}") {
        const val ARG_FORMATION = "formation"

        fun build(formationId: String): String = "wc_free/${Uri.encode(formationId)}"
    }

    /** The tournament. No arguments: the run lives in WorldCupRun, as a season's lives in ActiveRun. */
    data object WorldCupTournament : DreamXiDestination("wc_tournament")

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
