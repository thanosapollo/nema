package org.thanosapollo.nema

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun DatabaseStartupScreen(
    state: DatabaseStartup,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val reset = state == DatabaseStartup.RESET_REQUIRED || state == DatabaseStartup.RESET_FAILED
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(if (reset) R.string.database_reset_title else R.string.database_startup_title),
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(when (state) {
                DatabaseStartup.RESET_REQUIRED, DatabaseStartup.RESET_FAILED -> R.string.database_reset_warning
                DatabaseStartup.NEWER -> R.string.database_newer
                DatabaseStartup.OPENING -> R.string.database_opening
                else -> R.string.database_startup_failed
            }))
            if (state == DatabaseStartup.RESET_FAILED) Text(stringResource(R.string.database_reset_failed))
            if (state != DatabaseStartup.OPENING) {
                Button(onClick = if (reset) onReset else onRetry) {
                    Text(stringResource(if (reset) R.string.database_reset_continue else R.string.database_retry))
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.database_cancel)) }
        }
    }
}
