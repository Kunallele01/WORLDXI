package com.dreamxi.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Annotated for Hilt so the dependency graph (Repository
 * implementations wrapping Supabase + Room, ViewModels, etc.) is available app-wide.
 */
@HiltAndroidApp
class DreamXiApplication : Application()
