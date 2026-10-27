package com.example.consenzy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.consenzy.session.AppMode
import com.example.consenzy.session.ConsenzySessions
import com.example.consenzy.session.SessionBackend
import com.example.consenzy.session.SignedInSession
import com.example.consenzy.ui.common.describe
import `in`.onfinance.consent.core.ActingAs
import kotlinx.coroutines.launch

/** The screens of the example. Each journey is its own file under ui/. */
enum class Screen(val title: String) {
    HOME("Consenzy example"),
    CONSENT("1. Consent collection"),
    CHECK("2. Check before use"),
    PREFERENCES("3. Preferences centre"),
    RIGHTS("4. Rights requests"),
    GUARDIAN("5. Guardian consent"),
}

/** App-level state: the chosen mode, the signed-in customer and the current screen. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    var mode by mutableStateOf(AppMode.IN_APP_DEMO)
        private set
    var signedIn by mutableStateOf<SignedInSession?>(null)
        private set
    var screen by mutableStateOf(Screen.HOME)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val backend: SessionBackend get() = getApplication<ExampleApplication>().backend(mode)

    fun selectMode(newMode: AppMode) {
        if (signedIn == null) {
            mode = newMode
            error = null
        }
    }

    /** Sign-in: ask the backend for a `cps_` session and build the SDK client (see ConsenzySessions). */
    fun signIn(externalRef: String, actingAs: ActingAs) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                signedIn = ConsenzySessions.open(backend, externalRef, actingAs)
            } catch (e: Exception) {
                error = "Could not sign in: ${e.describe()}"
            } finally {
                busy = false
            }
        }
    }

    /** Logout: `ConsentSession.logout()` revokes the `cps_` token on the server, then the app forgets the session. */
    fun signOut() {
        val current = signedIn ?: return
        busy = true
        viewModelScope.launch {
            try {
                current.logout()
            } catch (e: Exception) {
                error = "Logout failed (signed out locally anyway): ${e.describe()}"
            } finally {
                signedIn = null
                screen = Screen.HOME
                busy = false
            }
        }
    }
}
