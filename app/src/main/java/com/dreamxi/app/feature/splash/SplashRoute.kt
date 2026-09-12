package com.dreamxi.app.feature.splash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Entry point for the intro.
 *
 * Stateless and dependency-free on purpose: the opening must never wait on the
 * network, a repository or a ViewModel. It runs its two seconds and leaves,
 * whatever the rest of the app is doing behind it.
 */
@Composable
fun SplashRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SplashScreen(onFinished = onFinished, modifier = modifier)
}
