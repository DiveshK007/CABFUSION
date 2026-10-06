package com.cabfusion.app.data

import android.content.Context
import com.cabfusion.app.BuildConfig
import com.cabfusion.app.domain.MatchingService
import com.google.firebase.FirebaseApp

/** Hand-rolled dependency container (small app, no DI framework needed). */
object ServiceLocator {
    lateinit var maps: MapsRepository private set
    lateinit var backend: Backend private set
    lateinit var matching: MatchingService private set
    private lateinit var appContext: Context

    /** Instrumented tests call this to force the demo backend even when Firebase is configured. */
    @Volatile var forceDemo = false

    fun init(context: Context) {
        appContext = context.applicationContext
        maps = MapsRepository()
        backend = pickBackend()
        matching = MatchingService(backend, maps)
    }

    fun useDemoBackend() {
        forceDemo = true
        backend = LocalBackend(appContext, maps)
        matching = MatchingService(backend, maps)
    }

    private fun pickBackend(): Backend {
        val firebaseReady = BuildConfig.HAS_FIREBASE && runCatching { FirebaseApp.getApps(appContext).isNotEmpty() }.getOrDefault(false)
        return if (firebaseReady && !forceDemo) FirebaseBackend() else LocalBackend(appContext, maps)
    }
}
