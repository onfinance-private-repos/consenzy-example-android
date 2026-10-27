package com.example.consenzy

import android.app.Application
import com.example.consenzy.demo.DemoBackend
import com.example.consenzy.session.AppMode
import com.example.consenzy.session.SessionBackend
import com.example.consenzy.session.SessionServerBackend

/** Holds the two backends for the life of the process, so demo data survives sign-out and screen changes. */
class ExampleApplication : Application() {
    /** In-app demo (default): synthetic data, no network. */
    val demoBackend: DemoBackend by lazy { DemoBackend.fromAssets(this) }

    /** Session server at BuildConfig.SESSION_SERVER_URL (gradle property consenzySessionServerUrl). */
    val sessionServerBackend: SessionServerBackend by lazy { SessionServerBackend(BuildConfig.SESSION_SERVER_URL) }

    fun backend(mode: AppMode): SessionBackend = when (mode) {
        AppMode.IN_APP_DEMO -> demoBackend
        AppMode.SESSION_SERVER -> sessionServerBackend
    }
}
