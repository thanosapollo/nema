package org.thanosapollo.nema

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.LoginFormInput
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.service.PendingActivationAuthority
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.service.privacySafeStatus
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.ui.AccountSettingsContent
import org.thanosapollo.nema.ui.LoginFormContent
import org.thanosapollo.nema.ui.PrimaryDestination
import org.thanosapollo.nema.ui.SessionBottomBar
import org.thanosapollo.nema.ui.chat.DirectChatContent
import org.thanosapollo.nema.ui.showLoginSessionChrome
import org.thanosapollo.nema.ui.theme.AppearanceScope
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.NemaTheme
import org.thanosapollo.nema.xmpp.transport.AccountId

class MainActivity : ComponentActivity() {
    private val activityResumed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val processToken = (application as NemaApplication).processToken
        val restoreChatRoute = shouldRestoreChatRoute(
            savedProcessToken = savedInstanceState?.getString(STATE_PROCESS_TOKEN),
            processToken = processToken,
        )
        setContent {
            AccountConnectionScreen(
                restoreChatRouteOnStart = restoreChatRoute,
                activityResumed = activityResumed.value,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed.value = true
    }

    override fun onPause() {
        activityResumed.value = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROCESS_TOKEN, (application as NemaApplication).processToken)
        super.onSaveInstanceState(outState)
    }
}

private const val STATE_PROCESS_TOKEN = "nema.process-token"

internal fun shouldRestoreChatRoute(savedProcessToken: String?, processToken: String): Boolean =
    savedProcessToken != null && savedProcessToken == processToken

private data class PendingActivationRequest(
    val configuration: AccountConfiguration,
    val credential: CharArray,
    val token: PendingActivationAuthority.Token,
)

internal fun credentialFormAccount(
    connectionState: ConnectionState,
    activeAccount: AccountConfiguration?,
    configuredAccounts: List<AccountConfiguration>,
): AccountConfiguration? {
    val targetId = (connectionState as? ConnectionState.NeedsCredentials)?.accountId
        ?: return activeAccount
    return configuredAccounts.firstOrNull { it.id == targetId }
}

internal fun sessionAccountForPresentation(
    activeAccount: AccountConfiguration?,
    connectionState: ConnectionState,
): AccountConfiguration? {
    val owner = when (connectionState) {
        ConnectionState.Stopped -> return activeAccount
        is ConnectionState.NeedsCredentials -> return null
        is ConnectionState.Switching -> {
            // Keep session shell mounted during switch so bottom destinations stay up.
            return activeAccount?.takeIf {
                it.id == connectionState.fromAccountId || it.id == connectionState.toAccountId
            }
        }
        is ConnectionState.Disconnected -> connectionState.accountId
        is ConnectionState.Connecting -> connectionState.accountId
        is ConnectionState.Connected -> connectionState.accountId
        is ConnectionState.ReconnectWait -> connectionState.accountId
        is ConnectionState.Disconnecting -> connectionState.accountId
        is ConnectionState.Failed -> connectionState.accountId
    }
    return activeAccount?.takeIf { it.id == owner }
}

internal fun xmppShareIntent(peerJid: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    val escapedPeer = Uri.encode(peerJid, "@")
    putExtra(Intent.EXTRA_TEXT, "xmpp:$escapedPeer")
}

internal fun appearanceFormAccountId(
    formAccount: AccountConfiguration?,
    addingAccount: Boolean,
): String? = if (addingAccount) null else formAccount?.id?.value

@Composable
internal fun rememberPendingBackgroundScope(): MutableState<AppearanceScope?> = rememberSaveable(
    saver = Saver(
        save = { pending -> saveBackgroundScope(pending.value) },
        restore = { saved -> mutableStateOf(restoreBackgroundScope(saved)) },
    ),
) {
    mutableStateOf<AppearanceScope?>(null)
}

internal fun restoreBackgroundScope(saved: Any): AppearanceScope? {
    val values = saved as? List<*> ?: return null
    return runCatching {
        when {
            values == listOf("app") -> AppearanceScope.App
            values.size == 2 && values[0] == "account" && values[1] is String ->
                AppearanceScope.Account(values[1] as String)
            values.size == 3 && values[0] == "conversation" &&
                values[1] is String && values[2] is String ->
                AppearanceScope.Conversation(values[1] as String, values[2] as String)
            else -> null
        }
    }.getOrNull()
}

internal fun consumeBackgroundSelection(
    pending: MutableState<AppearanceScope?>,
    uri: Uri?,
    onSelection: (AppearanceScope, Uri) -> Unit,
) {
    val target = pending.value
    try {
        if (target != null && uri != null) onSelection(target, uri)
    } finally {
        pending.value = null
    }
}

private fun saveBackgroundScope(scope: AppearanceScope?): List<String> = when (scope) {
    null -> emptyList()
    AppearanceScope.App -> listOf("app")
    is AppearanceScope.Account -> listOf("account", scope.accountId)
    is AppearanceScope.Conversation ->
        listOf("conversation", scope.accountId, scope.canonicalBarePeer)
}

@Composable
internal fun rememberPrimaryDestination(processToken: String): MutableState<PrimaryDestination> = rememberSaveable(
    saver = Saver(
        save = { destination -> listOf(processToken, destination.value.name) },
        restore = { saved ->
            val restored = saved.takeIf { it.size == 2 && it[0] == processToken }
                ?.get(1)
                ?.let(PrimaryDestination::fromSaved)
                ?: PrimaryDestination.HOME
            mutableStateOf(restored)
        },
    ),
) {
    mutableStateOf(PrimaryDestination.HOME)
}

@Composable
private fun AccountConnectionScreen(restoreChatRouteOnStart: Boolean, activityResumed: Boolean) {
    val context = LocalContext.current
    val application = context.applicationContext as NemaApplication
    val scope = rememberCoroutineScope()
    val connectionState by application.sessionRuntime.state.collectAsState()
    val activeAccount by application.sessionRuntime.activeAccount.collectAsState(initial = null)
    val configuredAccounts by application.sessionRuntime.configuredAccounts.collectAsState(initial = emptyList())
    var destination by rememberPrimaryDestination(application.processToken)
    fun selectDestination(next: PrimaryDestination) {
        destination = next
    }
    var addingAccount by remember { mutableStateOf(false) }
    val backgroundTarget = rememberPendingBackgroundScope()
    var appearanceMessage by remember { mutableStateOf<String?>(null) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        consumeBackgroundSelection(backgroundTarget, uri) { target, selectedUri ->
            scope.launch {
                appearanceMessage = if (application.appearanceRepository.setBackground(target, selectedUri)) {
                    null
                } else {
                    "Background access unavailable"
                }
            }
        }
    }
    val account = sessionAccountForPresentation(activeAccount, connectionState)
    if (account != null && connectionState !is ConnectionState.NeedsCredentials && !addingAccount) {
        val presenter = remember(account.id) {
            DirectChatPresenter(
                account = account,
                repository = application.chatRepository,
                scope = scope,
                enqueue = application.sessionRuntime::enqueueDirect,
                retry = application.sessionRuntime::retryUncertain,
                ensurePeerIdentities = application.sessionRuntime::ensurePeerIdentities,
                joinMuc = application.sessionRuntime::joinMuc,
                observeRoom = { peer -> application.sessionRuntime.rooms.observe(account.id.value, peer) },
                restoreRouteOnStart = restoreChatRouteOnStart,
            )
        }
        DisposableEffect(presenter) {
            onDispose(presenter::close)
        }
        val chatState by presenter.state.collectAsState()
        val readReceiptsEnabled by remember(account.id) {
            application.messagingPreferences.readReceipts(account.id.value)
        }.collectAsState(initial = false)
        val blockingSession = (connectionState as? ConnectionState.Connected)?.let {
            SessionIdentity(it.accountId, it.generation)
        }
        var appearanceScope by remember(account.id.value, chatState.selectedPeer) {
            mutableStateOf<AppearanceScope>(AppearanceScope.App)
        }
        fun updateAppearance(
            transform: (AppearanceSpec) -> AppearanceSpec,
        ) {
            val target = appearanceScope
            scope.launch {
                appearanceMessage = if (application.appearanceRepository.update(target, transform)) {
                    null
                } else {
                    "Appearance was not saved"
                }
            }
        }
        fun stopSession() {
            context.startService(
                XmppConnectionService.actionIntent(context, XmppConnectionService.ACTION_STOP),
            )
        }
        fun signOutSession() {
            context.startService(
                XmppConnectionService.actionIntent(context, XmppConnectionService.ACTION_SIGN_OUT),
            )
        }
        LaunchedEffect(account.id) {
            if (application.sessionRuntime.claimAutomaticConnectionStart()) {
                context.startForegroundService(
                    XmppConnectionService.actionIntent(context, XmppConnectionService.ACTION_CONNECT),
                )
            }
        }
        val shellAppearance = AppearanceSpec.DEFAULT
        NemaTheme(shellAppearance) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (destination != PrimaryDestination.HOME || chatState.selectedPeer == null) {
                        SessionBottomBar(
                            selected = destination,
                            onSelect = ::selectDestination,
                        )
                    }
                },
            ) { contentPadding ->
                when (destination) {
                    PrimaryDestination.HOME -> {
                        DirectChatContent(
                            state = chatState,
                            connectionStatus = privacySafeStatus(connectionState),
                            onSelectPeer = presenter::selectPeer,
                            onJoinRoom = presenter::joinRoom,
                            onCloseConversation = presenter::closeConversation,
                            onDraftChange = presenter::updateDraft,
                            onSend = presenter::sendDraft,
                            onSendAsNewThread = presenter::sendDraftAsNewThread,
                            onStartNewThread = presenter::startNewThread,
                            onContinueThread = presenter::continueThread,
                            onStartChildThread = presenter::startChildThread,
                            onStartThreadFrom = presenter::startThreadFrom,
                            onCloseThread = presenter::closeThread,
                            onRenameThread = presenter::renameThread,
                            blockingSession = blockingSession,
                            onSavePeerNickname = { key, nickname ->
                                if (key.accountId != account.id.value) {
                                    false
                                } else {
                                    try {
                                        application.peerIdentityStore.saveLocalNickname(
                                            key.accountId,
                                            key.canonicalBarePeer,
                                            nickname,
                                        )
                                        true
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            },
                            onLoadPeerBlocking = application.sessionRuntime::peerBlockingState,
                            onSetPeerBlocked = application.sessionRuntime::setPeerBlocked,
                            onSharePeer = { peerJid ->
                                context.startActivity(
                                    Intent.createChooser(
                                        xmppShareIntent(peerJid),
                                        "Share XMPP address",
                                    ),
                                )
                            },
                            ownLabel = account.bareJid.value,
                            onOpenOwnProfile = { selectDestination(PrimaryDestination.SETTINGS) },
                            onUploadFile = { name, mime, bytes ->
                                application.sessionRuntime.uploadHttpFile(
                                    org.thanosapollo.nema.xmpp.httpupload.LocalUploadRequest(name, mime, bytes),
                                )
                            },
                            readReceiptsEnabled = readReceiptsEnabled,
                            activityResumed = activityResumed,
                            onMessageDisplayed = { message ->
                                val peer = chatState.selectedPeer
                                val target = message.markerTargetId
                                if (peer == null || target == null || message.outgoing || message.groupChat) {
                                    false
                                } else {
                                    application.sessionRuntime.markDisplayed(
                                        account.id.value,
                                        peer,
                                        target,
                                    )
                                }
                            },
                            modifier = Modifier
                                .padding(contentPadding)
                                .statusBarsPadding(),
                        )
                    }
                    PrimaryDestination.SETTINGS -> {
                        AccountSettingsContent(
                            activeAccountId = account.id,
                            connectionStatus = privacySafeStatus(connectionState),
                            appearanceScope = appearanceScope,
                            appearance = shellAppearance,
                            appearanceInherited = appearanceScope !is AppearanceScope.App &&
                                application.appearanceRepository.stored(appearanceScope) == null,
                            conversationPeer = chatState.selectedPeer,
                            appearanceMessage = appearanceMessage,
                            onSelectAppearanceScope = {
                                appearanceScope = it
                                appearanceMessage = null
                            },
                            onSetThemeMode = { mode -> updateAppearance { it.copy(themeMode = mode) } },
                            onSetPalette = { palette -> updateAppearance { it.copy(palette = palette) } },
                            onSetTextScale = { scale -> updateAppearance { it.copy(textScale = scale) } },
                            onSetUiScale = { scale -> updateAppearance { it.copy(uiScale = scale) } },
                            onChooseBackground = {
                                backgroundTarget.value = appearanceScope
                                backgroundPicker.launch(arrayOf("image/*"))
                            },
                            onClearBackground = {
                                val target = appearanceScope
                                scope.launch {
                                    application.appearanceRepository.clearBackground(target)
                                }
                            },
                            onUseInheritedAppearance = {
                                val target = appearanceScope
                                scope.launch { application.appearanceRepository.clear(target) }
                            },
                            readReceiptsEnabled = readReceiptsEnabled,
                            onSetReadReceiptsEnabled = { enabled ->
                                application.messagingPreferences.setReadReceipts(account.id.value, enabled)
                            },
                            accounts = configuredAccounts,
                            switchingAccount = connectionState is ConnectionState.Switching,
                            onSelectAccount = { accountId ->
                                selectDestination(PrimaryDestination.HOME)
                                context.startForegroundService(
                                    XmppConnectionService.activateIntent(context, accountId),
                                )
                            },
                            onAddAccount = {
                                selectDestination(PrimaryDestination.HOME)
                                addingAccount = true
                            },
                            onStop = ::stopSession,
                            onSignOut = ::signOutSession,
                            modifier = Modifier
                                .padding(contentPadding)
                                .statusBarsPadding(),
                        )
                    }
                }
            }
        }
        return
    }
    var bareJid by remember { mutableStateOf("") }
    var authenticationId by remember { mutableStateOf("") }
    var authorizationId by remember { mutableStateOf("") }
    var serviceDomain by remember { mutableStateOf("") }
    var networkHost by remember { mutableStateOf("") }
    var networkPort by remember { mutableStateOf(LoginFormInput.DEFAULT_NETWORK_PORT) }
    var password by remember { mutableStateOf("") }
    var advancedOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingActivation by remember { mutableStateOf<PendingActivationRequest?>(null) }
    val showSessionChrome = showLoginSessionChrome(
        hasSavedAccount = activeAccount != null,
        connectionState = connectionState,
    )

    fun formInput() = LoginFormInput(
        bareJid = bareJid,
        authenticationId = authenticationId,
        authorizationId = authorizationId,
        serviceDomain = serviceDomain,
        networkHost = networkHost,
        networkPort = networkPort,
    )

    fun persistAndActivate(request: PendingActivationRequest) {
        pendingActivation = null
        application.sessionRuntime.claimAutomaticConnectionStart()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val emitted = application.sessionRuntime.prepareActivation(
                    token = request.token,
                    configuration = request.configuration,
                    credential = request.credential,
                    emitActivation = { accountId ->
                        context.startForegroundService(
                            XmppConnectionService.activateIntent(context, accountId),
                        )
                    },
                )
                if (emitted) {
                    message = null
                    addingAccount = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Check account settings"
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingActivation
        if (granted && request != null) {
            persistAndActivate(request)
        } else {
            request?.credential?.fill('\u0000')
            pendingActivation = null
            message = "Notifications are required for a visible connection"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            application.sessionRuntime.invalidatePendingActivation()
            pendingActivation?.credential?.fill('\u0000')
        }
    }

    val formAccount = credentialFormAccount(connectionState, activeAccount, configuredAccounts)
    LaunchedEffect(formAccount, addingAccount) {
        if (addingAccount) return@LaunchedEffect
        val account = formAccount ?: return@LaunchedEffect
        val form = LoginFormInput.fromAccount(account)
        bareJid = form.bareJid
        authenticationId = form.authenticationId
        authorizationId = form.authorizationId
        serviceDomain = form.serviceDomain
        networkHost = form.networkHost
        networkPort = form.networkPort
    }

    NemaTheme(AppearanceSpec.DEFAULT) {
    LoginFormContent(
        bareJid = bareJid,
        onBareJidChange = { bareJid = it },
        password = password,
        onPasswordChange = { password = it },
        advancedOpen = advancedOpen,
        onAdvancedToggle = { advancedOpen = !advancedOpen },
        authenticationId = authenticationId,
        onAuthenticationIdChange = { authenticationId = it },
        authorizationId = authorizationId,
        onAuthorizationIdChange = { authorizationId = it },
        serviceDomain = serviceDomain,
        onServiceDomainChange = { serviceDomain = it },
        networkHost = networkHost,
        onNetworkHostChange = { networkHost = it },
        networkPort = networkPort,
        onNetworkPortChange = { networkPort = it },
        message = message,
        showSessionChrome = showSessionChrome,
        connectionStatus = if (showSessionChrome) privacySafeStatus(connectionState) else null,
        signInEnabled = password.isNotEmpty(),
        onSignIn = {
            val plaintext = password.toCharArray()
            password = ""
            try {
                val configuration = formInput().toConfiguration(AccountId.require(UUID.randomUUID().toString()))
                val request = PendingActivationRequest(
                    configuration,
                    plaintext,
                    application.sessionRuntime.beginPendingActivation(),
                )
                if (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    persistAndActivate(request)
                } else {
                    pendingActivation?.credential?.fill('\u0000')
                    pendingActivation = request
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {
                plaintext.fill('\u0000')
                message = "Check account settings"
            }
        },
        onCancelAddAccount = if (addingAccount) {
            {
                application.sessionRuntime.invalidatePendingActivation()
                pendingActivation?.credential?.fill('\u0000')
                pendingActivation = null
                password = ""
                message = null
                addingAccount = false
            }
        } else {
            null
        },
        onStop = {
            application.sessionRuntime.invalidatePendingActivation()
            pendingActivation?.credential?.fill('\u0000')
            pendingActivation = null
            password = ""
            addingAccount = false
            context.startService(
                XmppConnectionService.actionIntent(context, XmppConnectionService.ACTION_STOP),
            )
        },
        onSignOut = {
            application.sessionRuntime.invalidatePendingActivation()
            pendingActivation?.credential?.fill('\u0000')
            pendingActivation = null
            password = ""
            addingAccount = false
            context.startService(
                XmppConnectionService.actionIntent(context, XmppConnectionService.ACTION_SIGN_OUT),
            )
        },
    )
    }
}
