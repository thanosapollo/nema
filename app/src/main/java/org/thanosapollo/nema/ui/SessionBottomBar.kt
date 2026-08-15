package org.thanosapollo.nema.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun SessionBottomBar(
    selected: PrimaryDestination,
    onSelect: (PrimaryDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier.semantics { contentDescription = "Primary destinations" }) {
        PrimaryDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

private val PrimaryDestination.label: String
    get() = when (this) {
        PrimaryDestination.HOME -> "Home"
        PrimaryDestination.SETTINGS -> "Settings"
    }

private val PrimaryDestination.icon: ImageVector
    get() = when (this) {
        PrimaryDestination.HOME -> Icons.Filled.Home
        PrimaryDestination.SETTINGS -> Icons.Filled.Settings
    }
