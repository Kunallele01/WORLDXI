package com.dreamxi.app.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Shows the pages, and remembers they have been seen however they were left.
 *
 * Marked seen on Skip and on finishing alike: a player who skipped has made their
 * choice, and showing it again next launch would be the app ignoring it.
 */
@Composable
fun OnboardingRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    OnboardingScreen(
        onDone = {
            viewModel.markSeen()
            onDone()
        },
        modifier = modifier,
    )
}
