package org.thanosapollo.nema

import android.app.Application
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.thanosapollo.nema.storage.DatabaseCompatibility
import org.thanosapollo.nema.storage.inspectAlphaDatabase
import org.thanosapollo.nema.storage.resetAlphaDatabase
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.credentials.AndroidKeystoreCredentialCipher
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.credentials.NoBackupCredentialBlobStore
import org.thanosapollo.nema.service.SessionRuntime
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.ui.theme.AppearanceRepository
import org.thanosapollo.nema.ui.MessagingPreferencesRepository
import org.thanosapollo.nema.update.UpdateCoordinator
import org.thanosapollo.nema.update.createAndroidUpdateRepository
import org.thanosapollo.nema.xmpp.smack.SmackAndroid
import org.thanosapollo.nema.xmpp.smack.installNemaCarbonProvider
import org.thanosapollo.nema.xmpp.smack.installNemaMamResultProvider
import org.thanosapollo.nema.xmpp.smack.installNemaMucUserProvider
import org.thanosapollo.nema.xmpp.smack.installNemaSidProviders

internal enum class DatabaseStartup { OPENING, READY, RESET_REQUIRED, RESET_FAILED, FAILED, NEWER }

open class NemaApplication : Application() {
    private val mutableDatabaseStartup = MutableStateFlow(DatabaseStartup.OPENING)
    internal val databaseStartup = mutableDatabaseStartup.asStateFlow()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val processToken: String = UUID.randomUUID().toString()
    internal val installResumeGate = InstallResumeGate()
    lateinit var database: NemaDatabase
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var sessionRuntime: SessionRuntime
        private set
    lateinit var peerIdentityStore: PeerIdentityStore
        private set
    lateinit var appearanceRepository: AppearanceRepository
        private set
    lateinit var messagingPreferences: MessagingPreferencesRepository
        private set
    lateinit var updates: UpdateCoordinator
        private set
    internal lateinit var installResumeController: InstallResumeController
        private set
    override fun onCreate() {
        super.onCreate()
        updates = UpdateCoordinator(
            applicationScope,
            createRepository = { createAndroidUpdateRepository(applicationContext, installResumeGate) },
        )
        installResumeController = InstallResumeController(applicationScope, installResumeGate) { handoff ->
            updates.settleInstallOnResume(handoff)
        }
        SmackAndroid.initialize(applicationContext)
        installNemaCarbonProvider()
        installNemaMucUserProvider()
        installNemaMamResultProvider()
        installNemaSidProviders()
        appearanceRepository = AppearanceRepository.create(applicationContext)
        messagingPreferences = MessagingPreferencesRepository.create(applicationContext)
        retryDatabaseStartup()
    }

    internal fun retryDatabaseStartup() = applicationScope.launch(Dispatchers.IO) { openDatabase() }

    internal fun resetDatabaseAndContinue() = applicationScope.launch(Dispatchers.IO) { openDatabase(reset = true) }

    @Synchronized
    private fun openDatabase(reset: Boolean = false) {
        val previous = mutableDatabaseStartup.value
        if (previous == DatabaseStartup.READY) return
        if (reset && previous != DatabaseStartup.RESET_REQUIRED && previous != DatabaseStartup.RESET_FAILED) return
        mutableDatabaseStartup.value = DatabaseStartup.OPENING
        if (reset) {
            // No session, repository observer, or Room handle exists while this gate is closed.
            try { resetAlphaDatabase(this) } catch (_: Exception) {
                mutableDatabaseStartup.value = DatabaseStartup.RESET_FAILED
                return
            }
        }
        try {
            when (inspectAlphaDatabase(this)) {
                DatabaseCompatibility.INCOMPATIBLE -> {
                    mutableDatabaseStartup.value = DatabaseStartup.RESET_REQUIRED
                    return
                }
                DatabaseCompatibility.NEWER -> {
                    mutableDatabaseStartup.value = DatabaseStartup.NEWER
                    return
                }
                DatabaseCompatibility.COMPATIBLE -> Unit
            }
            val opened = NemaDatabase.create(applicationContext)
            try {
                // Room is lazy: force validation before any ordinary database/session consumer.
                opened.openHelper.writableDatabase
                initializeDatabaseConsumers(opened)
            } catch (failure: Exception) {
                opened.close()
                throw failure
            }
            mutableDatabaseStartup.value = DatabaseStartup.READY
        } catch (_: Exception) {
            // IO, corruption and unclassified startup failures never grant reset permission.
            mutableDatabaseStartup.value = DatabaseStartup.FAILED
        }
    }

    private fun initializeDatabaseConsumers(opened: NemaDatabase) {
        database = opened
        chatRepository = ChatRepository(database)
        peerIdentityStore = PeerIdentityStore(database.messageDao())
        sessionRuntime = createSessionRuntime(opened)
    }

    internal open fun createSessionRuntime(opened: NemaDatabase): SessionRuntime {
        val credentials = CredentialVault(
            NoBackupCredentialBlobStore(applicationContext),
            AndroidKeystoreCredentialCipher(),
        )
        return SessionRuntime(
            accounts = AccountRepository(opened.accountDao()),
            credentials = credentials,
            messages = MessageStore(opened),
            peerIdentities = peerIdentityStore,
        )
    }
}
