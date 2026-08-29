package org.thanosapollo.nema

import android.app.Application
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

class NemaApplication : Application() {
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
        database = NemaDatabase.create(applicationContext)
        val accounts = AccountRepository(database.accountDao())
        chatRepository = ChatRepository(database)
        peerIdentityStore = PeerIdentityStore(database.messageDao())
        appearanceRepository = AppearanceRepository.create(applicationContext)
        messagingPreferences = MessagingPreferencesRepository.create(applicationContext)
        val credentials = CredentialVault(
            NoBackupCredentialBlobStore(applicationContext),
            AndroidKeystoreCredentialCipher(),
        )
        sessionRuntime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = peerIdentityStore,
        )
    }
}
