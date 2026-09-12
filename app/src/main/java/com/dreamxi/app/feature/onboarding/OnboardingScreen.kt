package com.dreamxi.app.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dreamxi.app.core.ui.PlaceholderScreen

/**
 * Sign up / log in / guest entry. See PROJECT_SPEC_v2.md §5.4: a guest draft is
 * allowed with no account, with a prompt to sign up (email+password or Google
 * Sign-In via Credential Manager) shown afterward to save it.
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Onboarding", modifier = modifier)
}
