package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Presentational login form. Session chrome and advanced fields controlled by caller. */
@Composable
fun LoginFormContent(
    bareJid: String,
    onBareJidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    advancedOpen: Boolean,
    onAdvancedToggle: () -> Unit,
    authenticationId: String,
    onAuthenticationIdChange: (String) -> Unit,
    authorizationId: String,
    onAuthorizationIdChange: (String) -> Unit,
    serviceDomain: String,
    onServiceDomainChange: (String) -> Unit,
    networkHost: String,
    onNetworkHostChange: (String) -> Unit,
    networkPort: String,
    onNetworkPortChange: (String) -> Unit,
    message: String?,
    showSessionChrome: Boolean,
    connectionStatus: String?,
    signInEnabled: Boolean,
    onSignIn: () -> Unit,
    onCancelAddAccount: (() -> Unit)? = null,
    onStop: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nema", style = MaterialTheme.typography.headlineLarge)
        if (showSessionChrome && connectionStatus != null) {
            Text(connectionStatus, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = bareJid,
            onValueChange = onBareJidChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("JID (name@domain)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        TextButton(onClick = onAdvancedToggle) {
            Text(if (advancedOpen) "Hide advanced" else "Advanced")
        }
        if (advancedOpen) {
            OutlinedTextField(
                value = authenticationId,
                onValueChange = onAuthenticationIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Authentication identity") },
                singleLine = true,
            )
            OutlinedTextField(
                value = authorizationId,
                onValueChange = onAuthorizationIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Authorization identity (optional)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = serviceDomain,
                onValueChange = onServiceDomainChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("XMPP service domain") },
                singleLine = true,
            )
            OutlinedTextField(
                value = networkHost,
                onValueChange = onNetworkHostChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Network host override (optional)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = networkPort,
                onValueChange = onNetworkPortChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Network port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            enabled = signInEnabled,
        ) {
            Text("Sign in")
        }
        onCancelAddAccount?.let { cancel ->
            TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
        if (showSessionChrome) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onStop) { Text("Stop") }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
    }
}
