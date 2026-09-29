package org.thanosapollo.nema.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.BuildConfig

/** One app-owned entry, independent of account/login/database/navigation state. */
@Composable
fun AppUpdateHost(
    updates: UpdateCoordinator,
    resumed: Boolean,
    onInstallRequest: (UpdateInstallRequest) -> Unit,
    content: @Composable () -> Unit,
) {
    val state by updates.state.collectAsState()
    val request by updates.installRequest.collectAsState()
    val message by updates.message.collectAsState()
    var opened by rememberSaveable { mutableStateOf(false) }
    val visible = state.accepted?.value is AcceptedUpdate.Available
    val model = updateUiModel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), state)
    LaunchedEffect(request, resumed) {
        if (resumed) request?.let(onInstallRequest)
    }
    Column(Modifier.fillMaxSize()) {
        if (visible) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                TextButton(
                    onClick = { opened = true },
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().testTag("app-update"),
                ) {
                    Text(if (model.canDownload) "Nema update available · View" else "Nema update · View progress")
                }
            }
        }
        Box(Modifier.weight(1f).then(
            if (visible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
        )) { content() }
    }
    if (opened) {
        AlertDialog(
            onDismissRequest = { opened = false },
            title = { Text("Nema updates") },
            text = {
                UpdateControls(model, { updates.requestCheck() }, { updates.requestUpdate() },
                    { updates.requestUpdate() }, { updates.cancelUpdate() }, message,
                    Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { opened = false }) { Text("Close") } },
        )
    }
}

/** Shared by the app-level entry and Settings. Checking never downloads an APK. */
@Composable
fun UpdateControls(
    model: UpdateUiModel,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit = {},
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(model.currentVersion, modifier = Modifier.testTag("settings-row-update-status"))
        model.status?.let { Text(it) }
        message?.let { Text(it) }
        if (model.canDownload || model.canInstall) {
            Text("Android will ask you to allow updates from Nema and confirm installation.")
            Button(onClick = if (model.canInstall) onInstall else onDownload,
                modifier = Modifier.testTag("update-action")) { Text("Update") }
        }
        if (model.canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("update-cancel")) { Text("Cancel update") }
        }
        TextButton(onClick = onCheck, enabled = model.canCheck,
            modifier = Modifier.testTag("settings-row-check-update")) { Text("Check for updates") }
    }
}
