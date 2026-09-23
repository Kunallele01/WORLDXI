package com.dreamxi.app

import android.app.Application
import android.util.Log
import com.dreamxi.app.data.auth.SessionRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Annotated for Hilt so the dependency graph (Repository
 * implementations wrapping Supabase + Room, ViewModels, etc.) is available app-wide.
 */
@HiltAndroidApp
class DreamXiApplication : Application() {

    @Inject
    lateinit var session: SessionRepository

    /**
     * Outlives every screen on purpose: the session belongs to the app, not to
     * whichever activity happened to start it, and cancelling it on rotation
     * would leave a launch with no account.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Fire and forget, deliberately. A session is needed before anything can
        // be SAVED, and nothing can be saved in the first seconds of a cold
        // start anyway — so this must never delay the first frame, and an
        // offline launch must still reach the app rather than an error screen.
        // Every write checks for a session of its own.
        scope.launch {
            runCatching { session.ensureSession() }
                .onFailure { Log.w("DreamXI", "could not establish a session", it) }
        }
    }
}
