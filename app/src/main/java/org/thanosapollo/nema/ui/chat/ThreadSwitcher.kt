package org.thanosapollo.nema.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.RecentThread
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.threadNameError

internal fun destinationTitle(selected: ThreadRef?, threads: List<RecentThread>): String =
    if (selected == null) "Main" else threads.firstOrNull { it.thread == selected }?.title ?: "Thread"

/** One live chat remains behind this directory; selection is owned by the presenter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadSwitcher(
    occurrence: ChatRouteOccurrence,
    selected: ThreadRef?,
    threads: List<RecentThread>,
    onSelect: (ChatRouteOccurrence, ThreadRef?) -> Unit,
    onCreate: suspend (ChatRouteOccurrence, String) -> Boolean,
    onRename: suspend (ChatRouteOccurrence, RecentThread, String, org.thanosapollo.nema.xmpp.threads.DirectoryContext?) -> Boolean,
    mainUnreadCount: Int = 0,
    directory: org.thanosapollo.nema.xmpp.threads.DirectoryView = org.thanosapollo.nema.xmpp.threads.DirectoryView(),
    onRefresh: suspend (ChatRouteOccurrence) -> Unit = {},
    onArchive: suspend (ChatRouteOccurrence, RecentThread) -> Boolean = { _, _ -> false },
    onRetry: suspend (ChatRouteOccurrence) -> Boolean = { false },
    onKeepCurrent: suspend (ChatRouteOccurrence) -> Boolean = { false },
    onCreateShared: suspend (ChatRouteOccurrence, String, org.thanosapollo.nema.xmpp.threads.DirectoryContext) -> Boolean = { _, _, _ -> false },
) {
    var open by remember(occurrence) { mutableStateOf(false) }
    var showArchived by remember(occurrence) { mutableStateOf(false) }
    var editing by remember(occurrence) { mutableStateOf(false) }
    var authoredDirectory by remember(occurrence) { mutableStateOf(directory) }
    var renameTarget by remember(occurrence) { mutableStateOf<RecentThread?>(null) }
    var name by remember(occurrence) { mutableStateOf("") }
    var failure by remember(occurrence) { mutableStateOf<String?>(null) }
    var pending by remember(occurrence) { mutableStateOf(false) }
    // Each opened/edited form owns only its own submitted revision. The directory's
    // durable operation ID binds recovery to that submission, not whichever form is visible.
    var formRevision by remember(occurrence) { mutableIntStateOf(0) }
    var submittedRevision by remember(occurrence) { mutableStateOf<Int?>(null) }
    var submittedOperation by remember(occurrence) { mutableStateOf<String?>(null) }
    var settlement by remember(occurrence) { mutableStateOf<String?>(null) }
    LaunchedEffect(directory.mode, directory.pendingOperation) {
        if (directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.UNCERTAIN) {
            if (submittedRevision != null && submittedOperation == null) submittedOperation = directory.pendingOperation
        } else if (!pending && (directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.SHARED ||
                directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.ERROR)) {
            submittedRevision = null
            submittedOperation = null
        }
    }
    val scope = rememberCoroutineScope()
    fun recover(keepCurrent: Boolean) {
        val revision = submittedRevision.takeIf { submittedOperation != null && submittedOperation == directory.pendingOperation }
        pending = true
        scope.launch {
            try {
                val settled = if (keepCurrent) onKeepCurrent(occurrence) else onRetry(occurrence)
                if (settled) {
                    if (editing && revision != null && formRevision == revision) {
                        editing = false
                        renameTarget = null
                        name = ""
                        failure = null
                    }
                    submittedRevision = null
                    submittedOperation = null
                    settlement = if (keepCurrent) "Kept current shared state." else "Shared change confirmed."
                }
            } finally { pending = false }
        }
    }
    val named = threads.filter { it.locallyNamed }
        .sortedWith(compareBy<RecentThread> { it.title.lowercase() }.thenBy { it.thread.id.value })
    val unread = named.sumOf { it.unreadCount }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thread-switcher"),
    ) {
        Text(destinationTitle(selected, threads), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            if (mainUnreadCount > 0) Text("Main · $mainUnreadCount unread")
            Text(if (unread > 0) "Threads · $unread unread" else "Threads")
        }
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
    if (!open) return
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Threads", style = MaterialTheme.typography.titleLarge)
            Text(
                directory.explanation,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            settlement?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = { scope.launch { onRefresh(occurrence) } }, enabled = !pending) { Text("Refresh threads") }
                TextButton(onClick = { showArchived = !showArchived }) {
                    val archived = named.filter { it.shared?.archived == true }
                    Text(if (showArchived) "Active threads" else "Archived (${archived.size}) · ${archived.sumOf { it.unreadCount }} unread")
                }
            }
            if (directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.UNCERTAIN) {
                TextButton(enabled = !pending, onClick = {
                    recover(keepCurrent = false)
                }) { Text("Retry saved shared change") }
                if (directory.pendingOperation != null) {
                    Text("After checking the directory, you can keep its current state. This does not undo an applied change.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !pending, onClick = {
                        recover(keepCurrent = true)
                    }) { Text("Keep current shared state") }
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { formRevision++; name = it; failure = null; settlement = null },
                    label = { Text(if (renameTarget?.shared != null || (renameTarget == null && authoredDirectory.writable)) "Shared thread name"
                        else if (renameTarget == null) "Thread name" else "Local thread name") },
                    singleLine = true,
                    enabled = !pending,
                    isError = failure != null,
                    supportingText = failure?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth().testTag("thread-name-input"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { formRevision++; editing = false; failure = null }, enabled = !pending) { Text("Cancel") }
                    TextButton(
                        enabled = !pending,
                        onClick = {
                            failure = threadNameError(name)
                            if (failure == null) {
                                val submittedName = name
                                val target = renameTarget
                                val revision = formRevision
                                settlement = null
                                pending = true
                                scope.launch {
                                    val saved = try {
                                        if (target == null && authoredDirectory.writable) {
                                            val authored = authoredDirectory.context
                                            if (authored == null || authoredDirectory.context != directory.context || !directory.writable) false
                                            else {
                                                submittedRevision = revision
                                                submittedOperation = null
                                                onCreateShared(occurrence, submittedName, authored)
                                            }
                                        } else if (target == null && directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.LOCAL_ONLY) onCreate(occurrence, submittedName)
                                        else if (target != null) {
                                            if (target.shared != null && directory.writable && authoredDirectory.context == directory.context) {
                                                submittedRevision = revision
                                                submittedOperation = null
                                            }
                                            onRename(occurrence, target, submittedName, authoredDirectory.context)
                                        } else false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        false
                                    } finally {
                                        pending = false
                                    }
                                    if (saved) {
                                        editing = false
                                        open = false
                                    } else failure = if (directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.UNCERTAIN)
                                        "Shared change not confirmed. Use saved-operation retry." else "Not saved. Try again."
                                }
                            }
                        },
                    ) { Text(if (pending) "Saving…" else if (renameTarget == null) "Create thread" else "Save name") }
                }
            } else {
                TextButton(
                    enabled = !pending && (directory.writable || directory.mode == org.thanosapollo.nema.xmpp.threads.DirectoryMode.LOCAL_ONLY),
                    onClick = { formRevision++; authoredDirectory = directory; renameTarget = null; name = ""; failure = null; settlement = null; editing = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("new-named-thread"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New thread")
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(bottom = 16.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("Main") },
                    supportingContent = { if (mainUnreadCount > 0) Text("$mainUnreadCount unread") },
                    trailingContent = { if (selected == null) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    modifier = Modifier.testTag("thread-destination-main").selectable(
                        selected = selected == null, role = Role.Tab,
                        onClick = { open = false; onSelect(occurrence, null) },
                    ),
                )
            }
            items(named.filter { (it.shared?.archived == true) == showArchived }, key = { it.messageKind.name + ":" + it.thread.id.value }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val metadata = entry.shared
                        Text(listOfNotNull(
                            if (metadata != null) (if (metadata.retired) "Archived · previous directory" else "Shared") else "On this device",
                            metadata?.localAlias?.let { "Private alias: $it" },
                            if (entry.unreadCount > 0) "${entry.unreadCount} unread" else null,
                        ).joinToString(" · "))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected == entry.thread) Icon(Icons.Filled.Check, contentDescription = "Selected")
                            IconButton(
                                enabled = !pending && (entry.shared == null || (directory.writable && entry.shared.canModify && !entry.shared.retired)),
                                onClick = { formRevision++; authoredDirectory = directory; renameTarget = entry; name = entry.title; failure = null; settlement = null; editing = true },
                            ) { Icon(Icons.Filled.Edit, contentDescription = "Rename ${entry.title}" + if (entry.shared == null) " locally" else " for everyone") }
                            if (entry.shared != null) TextButton(
                                enabled = !pending && directory.writable && entry.shared.canModify && !entry.shared.retired,
                                onClick = { submittedRevision = null; submittedOperation = null; pending = true; scope.launch { try { onArchive(occurrence, entry) } finally { pending = false } } },
                            ) { Text(if (entry.shared.archived) "Restore" else "Archive") }
                        }
                    },
                    modifier = Modifier.testTag("thread-destination-${entry.thread.id.value}").selectable(
                        selected = selected == entry.thread, role = Role.Tab,
                        onClick = { open = false; onSelect(occurrence, entry.thread) },
                    ),
                )
            }
            if (named.isEmpty()) item {
                Text("Create a thread for a project, task, or topic.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
