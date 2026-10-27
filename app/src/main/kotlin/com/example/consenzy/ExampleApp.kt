package com.example.consenzy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.consenzy.ui.check.ConsentCheckScreen
import com.example.consenzy.ui.consent.ConsentCollectionScreen
import com.example.consenzy.ui.guardian.GuardianScreen
import com.example.consenzy.ui.home.HomeScreen
import com.example.consenzy.ui.preferences.PreferencesScreen
import com.example.consenzy.ui.rights.RightsRequestsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleApp(app: AppViewModel = viewModel()) {
    val signedIn = app.signedIn
    val screen = if (signedIn == null) Screen.HOME else app.screen
    BackHandler(enabled = screen != Screen.HOME) { app.screen = Screen.HOME }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) },
                    navigationIcon = {
                        if (screen != Screen.HOME) TextButton(onClick = { app.screen = Screen.HOME }) { Text("Back") }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (signedIn == null) {
                    HomeScreen(app)
                } else {
                    when (screen) {
                        Screen.HOME -> HomeScreen(app)
                        Screen.CONSENT -> ConsentCollectionScreen(signedIn)
                        Screen.CHECK -> ConsentCheckScreen(signedIn)
                        Screen.PREFERENCES -> PreferencesScreen(signedIn)
                        Screen.RIGHTS -> RightsRequestsScreen(signedIn)
                        Screen.GUARDIAN -> GuardianScreen(signedIn.backend)
                    }
                }
            }
        }
    }
}
