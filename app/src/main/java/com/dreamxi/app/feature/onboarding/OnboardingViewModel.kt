package com.dreamxi.app.feature.onboarding

import androidx.lifecycle.ViewModel
import com.dreamxi.app.data.OnboardingPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Remembers whether the player has been shown how the game works. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: OnboardingPrefs,
) : ViewModel() {

    fun hasSeenIntro(): Boolean = prefs.hasSeenIntro()

    fun markSeen() = prefs.markIntroSeen()
}
