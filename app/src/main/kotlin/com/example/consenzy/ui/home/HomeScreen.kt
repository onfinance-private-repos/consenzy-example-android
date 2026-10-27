package com.example.consenzy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.consenzy.AppViewModel
import com.example.consenzy.BuildConfig
import com.example.consenzy.Screen
import com.example.consenzy.session.AppMode
import com.example.consenzy.session.DemoCustomer
import com.example.consenzy.session.SignedInSession
import com.example.consenzy.ui.common.ChoiceRow
import com.example.consenzy.ui.common.ErrorMessage
import com.example.consenzy.ui.common.Field
import com.example.consenzy.ui.common.Hint
import com.example.consenzy.ui.common.SectionCard
import com.example.consenzy.ui.common.describe
import `in`.onfinance.consent.core.ActingAs

/** Sign-in (pick a mode and a demo customer) and, once signed in, the list of journeys plus logout. */
@Composable
fun HomeScreen(app: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val signedIn = app.signedIn
        if (signedIn == null) SignIn(app) else Journeys(app, signedIn)
        ErrorMessage(app.error)
    }
}

@Composable
private fun SignIn(app: AppViewModel) {
    SectionCard("Mode") {
        ChoiceRow(AppMode.entries, app.mode, { it.label }, app::selectMode)
        Hint(app.mode.description)
        if (app.mode == AppMode.SESSION_SERVER) Field("Session server", BuildConfig.SESSION_SERVER_URL)
    }

    var customers by remember(app.mode) { mutableStateOf<List<DemoCustomer>>(emptyList()) }
    var loadError by remember(app.mode) { mutableStateOf<String?>(null) }
    LaunchedEffect(app.mode) {
        try {
            customers = app.backend.demoCustomers()
        } catch (e: Exception) {
            loadError = "Could not load customers from the session server: ${e.describe()}"
        }
    }

    SectionCard("Sign in as a demo customer") {
        Hint(
            "In a real app the customer signs in with your own login and your backend decides who they are. " +
                "Here you pick a synthetic customer instead.",
        )
        ErrorMessage(loadError)
        customers.forEach { customer ->
            Text(customer.label, style = MaterialTheme.typography.titleSmall)
            Text(customer.externalRef, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { app.signIn(customer.externalRef, ActingAs.DATA_PRINCIPAL) },
                enabled = !app.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (customer.actsThroughGuardian) "Sign in as the child (data principal)" else "Sign in") }
            if (customer.actsThroughGuardian) {
                OutlinedButton(
                    onClick = { app.signIn(customer.externalRef, ActingAs.GUARDIAN) },
                    enabled = !app.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in as the parent / guardian") }
            }
        }
    }
}

@Composable
private fun Journeys(app: AppViewModel, signedIn: SignedInSession) {
    SectionCard("Signed in") {
        Field("Customer (external_ref)", signedIn.externalRef)
        Field("Acting as", signedIn.minted.actingAs)
        Field("Capabilities", signedIn.minted.capabilities.joinToString(", "))
        Field("Mode", "${app.mode.label} (${signedIn.minted.mode})")
        Field("SDK base URL", signedIn.minted.baseUrl)
        Field("Session expires", signedIn.minted.expiresAt)
    }
    SectionCard("Journeys") {
        listOf(Screen.CONSENT, Screen.CHECK, Screen.PREFERENCES, Screen.RIGHTS, Screen.GUARDIAN).forEach { screen ->
            Button(onClick = { app.screen = screen }, modifier = Modifier.fillMaxWidth()) { Text(screen.title) }
        }
    }
    OutlinedButton(onClick = app::signOut, enabled = !app.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Log out (revokes the session token)")
    }
}
