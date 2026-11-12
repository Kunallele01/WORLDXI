package com.dreamxi.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device has already been shown the "how it works" pages.
 *
 * A per-device convenience, deliberately NOT tied to the account: someone
 * signing in on a second phone should still be told what the game is, and a
 * guest has no account to hang it on. Losing it (clearing app data) costs one
 * viewing of four swipeable pages.
 */
@Singleton
class OnboardingPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("era_xi_onboarding", Context.MODE_PRIVATE)

    fun hasSeenIntro(): Boolean = prefs.getBoolean(KEY_SEEN_INTRO, false)

    fun markIntroSeen() {
        prefs.edit().putBoolean(KEY_SEEN_INTRO, true).apply()
    }

    private companion object {
        const val KEY_SEEN_INTRO = "seen_intro"
    }
}
