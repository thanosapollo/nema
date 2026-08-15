package org.thanosapollo.nema

import android.app.Application
import java.util.UUID
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
import org.thanosapollo.nema.xmpp.smack.SmackAndroid
import org.thanosapollo.nema.xmpp.smack.installNemaMamResultProvider

class NemaApplication : Application() {
    val processToken: String = UUID.randomUUID().toString()
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
    override fun onCreate() {
        super.onCreate()
        SmackAndroid.initialize(applicationContext)
        installNemaMamResultProvider()
        database = NemaDatabase.create(applicationContext)
        val accounts = AccountRepository(database.accountDao())
        chatRepository = ChatRepository(database)
        peerIdentityStore = PeerIdentityStore(database.messageDao())
        appearanceRepository = AppearanceRepository.create(applicationContext)
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
