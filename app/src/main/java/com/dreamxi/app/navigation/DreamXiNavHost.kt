package com.dreamxi.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dreamxi.app.feature.designsystem.DesignSystemScreen
import com.dreamxi.app.feature.draft.DraftRoute
import com.dreamxi.app.feature.finalize.FinalizeScreen
import com.dreamxi.app.feature.freemode.FreeModeRoute
import com.dreamxi.app.feature.history.HistoryScreen
import com.dreamxi.app.feature.home.HomeScreen
import com.dreamxi.app.feature.onboarding.OnboardingScreen
import com.dreamxi.app.feature.profile.ProfileScreen
import com.dreamxi.app.feature.results.ResultsScreen
import com.dreamxi.app.feature.season.SeasonRoute
import com.dreamxi.app.feature.splash.SplashRoute
import com.dreamxi.app.feature.setup.SetupRoute
import com.dreamxi.app.feature.simulating.SimulatingScreen
import com.dreamxi.app.feature.worldcup.WcDraftRoute
import com.dreamxi.app.feature.worldcup.WcFreeModeRoute
import com.dreamxi.app.feature.worldcup.WcTournamentRoute

/**
 * Wires up the full screen inventory from PROJECT_SPEC_v2.md §10.6.
 *
 * Setup is the start destination because it is the first screen with real
 * behaviour: it is where the league is chosen, and without that choice the
 * draft could only guess — which is exactly what it was doing, silently
 * running every draft in whichever league the database returned first.
 * Onboarding/Home slot in front of it once they exist.
 */
@Composable
fun DreamXiNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = DreamXiDestination.Splash.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(DreamXiDestination.Splash.route) {
            SplashRoute(
                // Popped as it leaves, so the system back gesture can never
                // return to an intro the user has already sat through.
                onFinished = {
                    navController.navigate(DreamXiDestination.Setup.route) {
                        popUpTo(DreamXiDestination.Splash.route) { inclusive = true }
                    }
                },
            )
        }

        composable(DreamXiDestination.DesignSystem.route) { DesignSystemScreen() }
        composable(DreamXiDestination.Onboarding.route) { OnboardingScreen() }
        composable(DreamXiDestination.Home.route) { HomeScreen() }

        composable(DreamXiDestination.Setup.route) {
            SetupRoute(
                onStartRun = { league, formation, rerolls ->
                    navController.navigate(
                        DreamXiDestination.Draft.build(league.id, league.name, formation.id, rerolls),
                    )
                },
                onStartFreeMode = { league, formation ->
                    navController.navigate(
                        DreamXiDestination.FreeMode.build(league.id, league.name, formation.id),
                    )
                },
                onStartWorldCup = { formation, rerolls ->
                    navController.navigate(DreamXiDestination.WorldCupDraft.build(formation.id, rerolls))
                },
                onStartWorldCupFreeMode = { formation ->
                    navController.navigate(DreamXiDestination.WorldCupFreeMode.build(formation.id))
                },
            )
        }

        composable(
            route = DreamXiDestination.WorldCupDraft.route,
            arguments = listOf(
                navArgument(DreamXiDestination.WorldCupDraft.ARG_FORMATION) { type = NavType.StringType },
                navArgument(DreamXiDestination.WorldCupDraft.ARG_REROLLS) { type = NavType.IntType },
            ),
        ) { entry ->
            val args = entry.arguments
            WcDraftRoute(
                formationId = args?.getString(DreamXiDestination.WorldCupDraft.ARG_FORMATION),
                rerollsAllowed = args?.getInt(DreamXiDestination.WorldCupDraft.ARG_REROLLS) ?: 2,
                onQuitRun = { navController.popBackStack() },
                onStartTournament = { navController.navigate(DreamXiDestination.WorldCupTournament.route) },
            )
        }

        composable(
            route = DreamXiDestination.WorldCupFreeMode.route,
            arguments = listOf(
                navArgument(DreamXiDestination.WorldCupFreeMode.ARG_FORMATION) { type = NavType.StringType },
            ),
        ) { entry ->
            WcFreeModeRoute(
                formationId = entry.arguments?.getString(DreamXiDestination.WorldCupFreeMode.ARG_FORMATION),
                onQuit = { navController.popBackStack() },
                onStartTournament = { navController.navigate(DreamXiDestination.WorldCupTournament.route) },
            )
        }

        composable(DreamXiDestination.WorldCupTournament.route) {
            WcTournamentRoute(
                // Back to Setup, not into the finished draft, for the season's reason.
                onDone = { navController.popBackStack(DreamXiDestination.Setup.route, inclusive = false) },
            )
        }

        composable(
            route = DreamXiDestination.Draft.route,
            arguments = listOf(
                navArgument(DreamXiDestination.Draft.ARG_LEAGUE_ID) { type = NavType.LongType },
                navArgument(DreamXiDestination.Draft.ARG_LEAGUE_NAME) { type = NavType.StringType },
                navArgument(DreamXiDestination.Draft.ARG_FORMATION) { type = NavType.StringType },
                navArgument(DreamXiDestination.Draft.ARG_REROLLS) { type = NavType.IntType },
            ),
        ) { entry ->
            val args = entry.arguments
            DraftRoute(
                leagueId = args?.getLong(DreamXiDestination.Draft.ARG_LEAGUE_ID),
                leagueName = args?.getString(DreamXiDestination.Draft.ARG_LEAGUE_NAME),
                formationId = args?.getString(DreamXiDestination.Draft.ARG_FORMATION),
                rerollsAllowed = args?.getInt(DreamXiDestination.Draft.ARG_REROLLS) ?: 2,
                onQuitRun = { navController.popBackStack() },
                onStartSeason = { navController.navigate(DreamXiDestination.Season.route) },
            )
        }

        composable(
            route = DreamXiDestination.FreeMode.route,
            arguments = listOf(
                navArgument("leagueId") { type = NavType.LongType },
                navArgument("leagueName") { type = NavType.StringType },
                navArgument("formation") { type = NavType.StringType },
            ),
        ) { entry ->
            val args = entry.arguments
            FreeModeRoute(
                leagueId = args?.getLong("leagueId"),
                leagueName = args?.getString("leagueName"),
                formationId = args?.getString("formation"),
                onQuit = { navController.popBackStack() },
                onStartSeason = { navController.navigate(DreamXiDestination.Season.route) },
            )
        }

        composable(DreamXiDestination.Finalize.route) { FinalizeScreen() }

        composable(DreamXiDestination.Season.route) {
            SeasonRoute(
                // Back to Setup, not back into the draft: the run is over, and
                // returning to a completed XI with nothing left to do is a
                // dead end the user has to press Back out of anyway.
                onFinish = {
                    navController.popBackStack(DreamXiDestination.Setup.route, inclusive = false)
                },
            )
        }
        composable(DreamXiDestination.Simulating.route) { SimulatingScreen() }
        composable(DreamXiDestination.Results.route) { ResultsScreen() }
        composable(DreamXiDestination.History.route) { HistoryScreen() }
        composable(DreamXiDestination.Profile.route) { ProfileScreen() }
    }
}
