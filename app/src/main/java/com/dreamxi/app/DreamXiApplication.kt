package com.dreamxi.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Annotated for Hilt so the dependency graph (Repository
 * implementations wrapping Supabase + Room, ViewModels, etc.) is available app-wide.
 */
@HiltAndroidApp
class DreamXiApplication : Application() {

    // NO SESSION IS TAKEN HERE, and that is the point.
    //
    // An earlier version signed in anonymously on launch. It worked, and it
    // quietly made the sign-in screen unreachable: by the time a new player saw
    // the front door he already had a guest account, so the screen waved him
    // straight through and nobody was ever offered an account. Creating a
    // session is now a thing the player does — signing in, creating an account,
    // or choosing to play as a guest — and AuthViewModel owns that decision.
}
