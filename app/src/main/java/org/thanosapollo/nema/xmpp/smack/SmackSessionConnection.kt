package org.thanosapollo.nema.xmpp.smack

import org.thanosapollo.nema.xmpp.threads.DirectoryAction
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryScope
import org.thanosapollo.nema.xmpp.threads.ThreadDirectorySnapshot
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryMutationResult
import org.thanosapollo.nema.xmpp.threads.SmackThreadDirectoryClient

import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.PresenceListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.UnparseableStanza
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException.XMPPErrorException
import org.jivesoftware.smack.filter.IQReplyFilter
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.parsing.ParsingExceptionCallback
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.blocking.BlockingCommandManager
import org.jivesoftware.smackx.blocking.element.BlockContactsIQ
import org.jivesoftware.smackx.blocking.element.BlockListIQ
import org.jivesoftware.smackx.blocking.element.UnblockContactsIQ
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.bookmarks.BookmarkManager
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.iqprivate.PrivateDataManager
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.jivesoftware.smackx.message_correct.element.MessageCorrectExtension
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatException
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.Occupant
import org.jivesoftware.smackx.muc.SubjectUpdatedListener
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.pubsub.Item
import org.jivesoftware.smackx.pubsub.PayloadItem
import org.jivesoftware.smackx.pubsub.PubSubManager
import org.jivesoftware.smackx.pubsub.SimplePayload
import org.jivesoftware.smackx.receipts.DeliveryReceipt
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.sid.StableUniqueStanzaIdManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import org.jivesoftware.smackx.vcardtemp.VCardManager
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.AccountTransportPolicy
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.account.orbotAddress
import org.thanosapollo.nema.xmpp.httpupload.AccountHttpTransfer
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionConnection
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.session.SessionFailure
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.bookmarks.BOOKMARKS2_NODE
import org.thanosapollo.nema.xmpp.bookmarks.Bookmark2Item
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot
import org.thanosapollo.nema.xmpp.bookmarks.STORAGE_BOOKMARKS_NAMESPACE
import org.thanosapollo.nema.xmpp.bookmarks.bookmark2Xml
import org.thanosapollo.nema.xmpp.bookmarks.mergeRoomBookmarks
import org.thanosapollo.nema.xmpp.bookmarks.parseBookmark2Conferences
import org.thanosapollo.nema.xmpp.bookmarks.parseStorageBookmarks
import org.thanosapollo.nema.xmpp.bookmarks.preferredRoomNick
import org.thanosapollo.nema.xmpp.muc.RoomOccupant
import org.thanosapollo.nema.xmpp.muc.RoomView
import org.thanosapollo.nema.xmpp.markers.ACKNOWLEDGED_ELEMENT
import org.thanosapollo.nema.xmpp.markers.CHAT_MARKERS_NAMESPACE
import org.thanosapollo.nema.xmpp.markers.DISPLAYED_ELEMENT
import org.thanosapollo.nema.xmpp.markers.RECEIPTS_NAMESPACE
import org.thanosapollo.nema.xmpp.markers.RECEIVED_ELEMENT
import org.thanosapollo.nema.xmpp.markers.addMarkable
import org.thanosapollo.nema.xmpp.markers.installNemaChatMarkerProviders
import org.thanosapollo.nema.xmpp.chatstates.CHAT_STATES_NAMESPACE
import org.thanosapollo.nema.xmpp.chatstates.ChatActivity
import org.thanosapollo.nema.xmpp.chatstates.addChatState
import org.thanosapollo.nema.xmpp.chatstates.chatActivityNamed
import org.thanosapollo.nema.xmpp.chatstates.installNemaChatStateProviders
import org.thanosapollo.nema.xmpp.reactions.REACTIONS_NAMESPACE
import org.thanosapollo.nema.xmpp.reactions.addReactions
import org.thanosapollo.nema.xmpp.reactions.installNemaReactionProviders
import org.thanosapollo.nema.xmpp.reactions.parseReactions
import org.thanosapollo.nema.xmpp.rtt.RTT_NAMESPACE
import org.thanosapollo.nema.xmpp.rtt.installNemaRttProviders
import org.thanosapollo.nema.xmpp.rtt.parseRtt
import org.thanosapollo.nema.xmpp.transport.IncomingChatState
import org.thanosapollo.nema.xmpp.transport.IncomingReactionEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText
import org.thanosapollo.nema.xmpp.httpupload.LocalUploadRequest
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile
import org.thanosapollo.nema.xmpp.oob.OutOfBandShare
import org.thanosapollo.nema.xmpp.oob.oobExtension
import org.thanosapollo.nema.xmpp.oob.oobShare
import org.thanosapollo.nema.xmpp.reply.addReply
import org.thanosapollo.nema.xmpp.reply.installNemaReplyProviders
import org.thanosapollo.nema.xmpp.reply.parseReplyBody
import org.thanosapollo.nema.xmpp.reply.REPLY_NAMESPACE
import org.thanosapollo.nema.xmpp.reply.replyReference
import org.thanosapollo.nema.xmpp.transport.ACCOUNT_ARCHIVE_SCOPE
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageDirection
import org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageRequest
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingMessageSignal
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage
import org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.OutgoingFailureEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingReactionEnvelope
import org.thanosapollo.nema.xmpp.transport.ReactionActor
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException
import org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities
import org.thanosapollo.nema.xmpp.transport.StanzaIdEnvelope
import org.thanosapollo.nema.xmpp.vcard.RemoteVCardPayload

class SmackSessionConnectionFactory : SessionConnectionFactory {
    override fun create(
        configuration: AccountConfiguration,
        identity: SessionIdentity,
        event: (SessionEvent) -> Unit,
    ): SessionConnection {
        requireNemaMucUserProvider()
        requireNemaMamResultProvider()
        installNemaOccupantIdProvider()
        installNemaCorrectionProvider()
        installNemaReplyProviders()
        installNemaChatMarkerProviders()
        installNemaChatStateProviders()
        installNemaReactionProviders()
        installNemaRttProviders()
        require(identity.accountId == configuration.id) { "Session account does not match configuration" }
        val policy = AccountTransportPolicy.forAccount(configuration)
        val torSockets = torSocketsFor(configuration, identity)
        val smackConfiguration = configurationFor(configuration, torSockets)
        val connection = (if (torSockets?.onionEligible == true) OnionXmppConnection(smackConfiguration, torSockets)
            else XMPPTCPConnection(smackConfiguration)).apply {
            setUseStreamManagement(false)
            setUseStreamManagementResumption(false)
            setParsingExceptionCallback(NemaParsingExceptionCallback)
        }
        advertiseNemaFeatures(connection)
        ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
        return SmackSessionConnection(
            connection = connection,
            authenticationId = configuration.authenticationId.value,
            expectedBareJid = configuration.bareJid.value,
            torSockets = torSockets,
            httpTransfer = AccountHttpTransfer(policy),
            event = event,
        )
    }

    companion object {
        private fun torSocketsFor(configuration: AccountConfiguration, identity: SessionIdentity =
            SessionIdentity(configuration.id, ConnectionGeneration.require(1))): TorSocketFactory? =
            if (AccountTransportPolicy.forAccount(configuration) == AccountTransportPolicy.TOR) {
                TorSocketFactory(configuration.networkEndpoint ?: NetworkEndpoint.create(configuration.serviceDomain.value, 5222),
                    owner = identity, serviceDomain = configuration.serviceDomain.value)
            } else null

        fun configurationFor(configuration: AccountConfiguration): XMPPTCPConnectionConfiguration =
            configurationFor(configuration, torSocketsFor(configuration))

        internal fun configurationFor(
            configuration: AccountConfiguration,
            torSockets: TorSocketFactory?,
        ): XMPPTCPConnectionConfiguration {
            require(torSockets == null || torSockets.matches(configuration)) { "Tor route does not match account endpoint" }
            val builder = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom(configuration.serviceDomain.value))
                .setUsernameAndPassword(configuration.authenticationId.value, null)
                .setSecurityMode(if (torSockets?.onionEligible == true) ConnectionConfiguration.SecurityMode.ifpossible
                    else ConnectionConfiguration.SecurityMode.required)
                .setHostnameVerifier(
                    XmppDomainCertificateVerifier(configuration.serviceDomain.value),
                )
            configuration.authorizationId?.let {
                builder.setAuthzid(JidCreate.entityBareFrom(it.value))
            }
            configuration.networkEndpoint?.let {
                builder.setHost(it.host)
                builder.setPort(it.port)
            }
            if (torSockets != null) {
                // This literal suppresses Smack's pre-socket DNS/SRV lookup. The socket adapter
                // exclusively uses its immutable intended host/port, not this placeholder.
                builder.setHostAddress(orbotAddress().address)
                builder.setSocketFactory(torSockets)
                builder.setDnssecMode(ConnectionConfiguration.DnssecMode.disabled)
            }
            return builder.build()
        }
    }
}

internal fun advertiseNemaFeatures(connection: XMPPConnection) {
    ServiceDiscoveryManager.getInstanceFor(connection).apply {
        addFeature(REPLY_NAMESPACE)
        addFeature(RECEIPTS_NAMESPACE)
        addFeature(CHAT_MARKERS_NAMESPACE)
        addFeature(CHAT_STATES_NAMESPACE)
        addFeature(MessageCorrectExtension.NAMESPACE)
        addFeature(REACTIONS_NAMESPACE)
        addFeature(RTT_NAMESPACE)
        addFeature(OCCUPANT_ID_NAMESPACE)
    }
}

internal fun resolveCarbonCapability(
    carbonManager: CarbonManager,
): CarbonCapabilityState = resolveCarbonCapability(
    supported = { carbonManager.isSupportedByServer },
    enabled = { carbonManager.carbonsEnabled },
    enable = carbonManager::enableCarbons,
)

internal fun resolveCarbonCapability(
    supported: () -> Boolean,
    enabled: () -> Boolean,
    enable: () -> Unit,
): CarbonCapabilityState {
    val isSupported = try {
        supported()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return CarbonCapabilityState.DISCOVERY_FAILED
    }
    if (!isSupported) return CarbonCapabilityState.UNSUPPORTED
    val alreadyEnabled = try {
        enabled()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return CarbonCapabilityState.ENABLE_FAILED
    }
    if (alreadyEnabled) return CarbonCapabilityState.ENABLED
    return try {
        enable()
        if (enabled()) {
            CarbonCapabilityState.ENABLED
        } else {
            CarbonCapabilityState.ENABLE_FAILED
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        CarbonCapabilityState.ENABLE_FAILED
    }
}

internal class SmackSessionConnection(
    private val connection: XMPPTCPConnection,
    private val authenticationId: String,
    private val expectedBareJid: String,
    private val event: (SessionEvent) -> Unit,
    private val torSockets: TorSocketFactory? = null,
    private val httpTransfer: AccountHttpTransfer = AccountHttpTransfer(AccountTransportPolicy.DIRECT),
    private val rosterHandoffFactory: (SessionAttemptIdentity) -> RosterAttemptHandoff = { attempt ->
        RosterHandoff(SmackRosterSource(Roster.getInstanceFor(connection)), attempt, event)
    },
) : SessionConnection {
    private val entryGate = Any()
    private val mucOrderInstance = java.util.UUID.randomUUID().toString()
    private val rosterLifecycle = RosterConnectionLifecycle(entryGate)
    private val revoked = AtomicBoolean(false)
    private val disconnectStarted = AtomicBoolean(false)
    private val stableIdGate = StableIdDiscoveryGate()
    private val roomStableIdAuthorities = RoomStableIdAuthorityRegistry()
    private val roomViewHandoff = RoomViewHandoff<UserStatusListener, PresenceListener, SubjectUpdatedListener, SessionEvent.RoomUpdated>(
        entryGate, roomStableIdAuthorities,
    )
    private val roomDiscoNames = ConcurrentHashMap<String, String>()
    private var carbonCapability: Pair<SessionAttemptIdentity, CarbonCapabilityState>? = null
    private val connectionListener = AttemptConnectionListener().also(connection::addConnectionListener)
    private val messageListener = StanzaListener { stanza ->
        if (revoked.get()) return@StanzaListener
        val wrapper = stanza as? Message ?: return@StanzaListener
        val attempt = synchronized(entryGate) {
            connectionListener.currentAttempt()
        } ?: return@StanzaListener
        val boundFullJid = connection.user?.toString() ?: return@StanzaListener
        val carrier = wrapper.classifyCarrier(expectedBareJid, boundFullJid)
        val failure = carrier.classifyOutgoingFailure(expectedBareJid)
        if (failure.consumed) {
            failure.failure?.let { event(SessionEvent.OutgoingFailure(attempt, it)) }
            return@StanzaListener
        }
        val message = carrier.toTrustedCarbonMessage(expectedBareJid) { room ->
            synchronized(entryGate) { roomStableIdAuthorities.snapshot(attempt, room) != null }
        } ?: return@StanzaListener
        val room = message.message.from?.asBareJid()?.toString()
        val roomFacts = copyRoomConsumerFacts(
            entryGate, roomStableIdAuthorities, attempt, room,
        )
        val carbonEffect = carrier.bodylessCarbonEffect()
        if (carrier is MessageCarrier.Direct || carbonEffect == BodylessCarbonEffect.SIGNAL) {
            message.message.toIncomingSignal(attempt, expectedBareJid)?.let {
                event(SessionEvent.Signal(attempt, it))
                return@StanzaListener
            }
        }
        if (carrier is MessageCarrier.Direct || carbonEffect == BodylessCarbonEffect.CHAT_STATE) {
            carrier.toIncomingChatState(attempt, expectedBareJid, roomFacts?.ownNick)?.let {
                event(SessionEvent.ChatState(attempt, it))
            }
        }
        if ((carrier is MessageCarrier.Direct || carbonEffect == BodylessCarbonEffect.RTT) &&
            wrapper.getExtension(MamResultExtension::class.java) == null
        ) {
            message.message.toIncomingRtt(attempt, expectedBareJid)?.let {
                event(SessionEvent.RealTimeText(attempt, it))
            }
        }
        if (carrier is MessageCarrier.Direct || carbonEffect == BodylessCarbonEffect.REACTION) {
            message.message.toIncomingReaction(
                attempt, expectedBareJid, roomFacts = roomFacts,
                liveCarrier = message.isRawLive(
                    wrapper.getExtension(MamResultExtension::class.java) != null,
                ),
            )?.let {
                event(SessionEvent.Reaction(attempt, it))
                if (message.message.body.isNullOrEmpty()) return@StanzaListener
            }
        }
        stableIdGate.accept(attempt, message).forEach(::deliver)
    }
    init {
        connection.addStanzaListener(messageListener, StanzaTypeFilter.MESSAGE)
    }

    override val isUsable: Boolean
        get() = !revoked.get() && (connection !is OnionXmppConnection ||
            connectionListener.currentAttempt()?.let(connection::permitsTransport) == true) && isSmackSessionUsable(
            expectedBareJid = expectedBareJid,
            boundBareJid = connection.user?.asBareJid()?.toString(),
            connected = connection.isConnected,
            authenticated = connection.isAuthenticated,
            disconnectedButResumable = connection.isDisconnectedButSmResumptionPossible,
        )

    override val onionWithoutTls: Boolean
        get() = connection is OnionXmppConnection && !connection.isSecureConnection && isUsable

    override fun revoke() {
        synchronized(entryGate) { if (!revoked.compareAndSet(false, true)) return }
        torSockets?.close()
        httpTransfer.close()
        rosterLifecycle.retireCurrent()
        roomViewHandoff.retireAllIf {
            stableIdGate.retireAll()
            connectionListener.localDisconnect()
            true
        }
    }

    override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
        establish(attempt, credential)
    }

    override suspend fun reconnect(attempt: SessionAttemptIdentity) {
        establish(attempt, null)
    }

    override fun updateAttempt(attempt: SessionAttemptIdentity) {
        torSockets?.invalidateAttempt()
        connectionListener.updateAttempt(attempt)
    }

    override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = runInterruptible(Dispatchers.IO) {
        val stanza = message.toSmackMessage()
        synchronized(entryGate) {
            val attempt = connectionListener.currentAttempt()
            if (revoked.get() ||
                attempt == null ||
                attempt.accountId != message.accountId ||
                attempt.generation != message.generation ||
                !isUsable ||
                (message.kind == MessageKind.GROUPCHAT &&
                    roomStableIdAuthorities.snapshot(attempt, message.recipient) == null)
            ) {
                throw SendNotAttemptedException()
            }
            entered()
        }
        connection.sendStanza(stanza)
    }

    override suspend fun sendSignal(signal: OutgoingMessageSignal) = runInterruptible(Dispatchers.IO) {
        val stanza = signal.toSmackMessage()
        synchronized(entryGate) {
            requireExactAttemptLocked(signal.accountId, signal.generation)
        }
        connection.sendStanza(stanza)
    }

    override suspend fun sendReaction(
        reaction: OutgoingReactionEnvelope,
    ) = runInterruptible(Dispatchers.IO) {
        val stanza = reaction.toSmackReaction()
        synchronized(entryGate) {
            requireExactAttemptLocked(reaction.accountId, reaction.generation)
            val current = requireNotNull(connectionListener.currentAttempt())
            if (reaction.messageKind == MessageKind.GROUPCHAT) {
                val facts = copyRoomConsumerFacts(
                    entryGate, roomStableIdAuthorities, current, reaction.recipient,
                )
                if (facts?.stableIdAuthority != reaction.recipient) {
                    throw SendNotAttemptedException()
                }
                connection.sendStanza(stanza)
            }
        }
        if (reaction.messageKind == MessageKind.CHAT) connection.sendStanza(stanza)
    }

    override suspend fun sendChatState(
        state: org.thanosapollo.nema.xmpp.transport.OutgoingChatState,
    ) = runInterruptible(Dispatchers.IO) {
        val stanza = state.toSmackMessage()
        synchronized(entryGate) {
            requireExactAttemptLocked(state.accountId, state.generation)
        }
        connection.sendStanza(stanza)
    }

    override suspend fun discoverCapabilities(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): SessionCapabilities = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val attempt = requireNotNull(connectionListener.currentAttempt())
        val carbons = synchronized(entryGate) {
            carbonCapability?.takeIf { it.first == attempt }?.second
        } ?: throw SendNotAttemptedException()
        resolveStableIdGateOnCapabilityFailure(stableIdGate, attempt, ::deliver) {
            val mam = archiveNetworkCall { MamManager.getInstanceFor(connection).isSupported }
            val stableIds = archiveNetworkCall {
                ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(
                    JidCreate.entityBareFrom(expectedBareJid),
                    StableUniqueStanzaIdManager.NAMESPACE,
                )
            }
            requireExactAttempt(accountId, generation)
            drainStableIdGate(stableIdGate, attempt, stableIds, ::deliver)
            SessionCapabilities(
                mamV2 = mam,
                carbons = carbons,
                stableIds = stableIdGate.support(attempt) == true,
            )
        }
    }

    override suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope =
        queryArchiveWithAuthorization(request, if (request.scope == ACCOUNT_ARCHIVE_SCOPE) null else
            roomArchiveAuthorization(request.scope) ?: throw SendNotAttemptedException())

    override suspend fun queryArchive(
        request: ArchivePageRequest, authorization: org.thanosapollo.nema.session.RoomArchiveAuthorization,
    ): ArchivePageEnvelope = queryArchiveWithAuthorization(request, authorization)

    private suspend fun queryArchiveWithAuthorization(
        request: ArchivePageRequest, authorization: org.thanosapollo.nema.session.RoomArchiveAuthorization?,
    ): ArchivePageEnvelope = runInterruptible(Dispatchers.IO) {
            val archiveJid = if (request.scope == ACCOUNT_ARCHIVE_SCOPE) {
                require(request.archiveAuthority == expectedBareJid) { "Unexpected archive authority" }
                expectedBareJid
            } else {
                require(request.scope == request.archiveAuthority) { "Room archive scope must match authority" }
                request.archiveAuthority
            }
            val (attempt, roomFacts) = synchronized(entryGate) {
                requireExactAttemptLocked(request.accountId, request.generation)
                val current = requireNotNull(connectionListener.currentAttempt())
                // Preserve the caller's incarnation across controller/IO waits; never
                // silently replace it with a newly joined membership of the same room.
                if (request.scope != ACCOUNT_ARCHIVE_SCOPE &&
                    (authorization == null || authorization.attempt != current ||
                        authorization.room != archiveJid || !authorization.admit())) {
                    throw SendNotAttemptedException()
                }
                current to if (request.scope == ACCOUNT_ARCHIVE_SCOPE) {
                    null
                } else {
                    copyRoomConsumerFacts(entryGate, roomStableIdAuthorities, current, archiveJid)
                        ?.takeIf { it.mamV2 } ?: throw SendNotAttemptedException()
                }
            }
            val trustStableIdsAtStart = if (request.scope == ACCOUNT_ARCHIVE_SCOPE) {
                stableIdGate.support(attempt) == true
            } else {
                roomFacts?.stableIdAuthority != null
            }
            val builder = MamManager.MamQueryArgs.builder().setResultPageSizeTo(request.pageSize)
            when (request.direction) {
                ArchivePageDirection.BOOTSTRAP -> builder.queryLastPage()
                ArchivePageDirection.BEFORE -> builder.beforeUid(requireNotNull(request.boundaryId))
                ArchivePageDirection.AFTER -> builder.afterUid(requireNotNull(request.boundaryId))
            }
            val queryPage = archiveNetworkCall {
                MamManager.getInstanceFor(
                    connection,
                    JidCreate.entityBareFrom(archiveJid),
                ).queryArchive(builder.build()).page
            }
            requireExactAttempt(request.accountId, request.generation)
            if (connectionListener.currentAttempt() != attempt) throw SendNotAttemptedException()
            val trustStableIds = trustStableIdsAtStart && if (request.scope == ACCOUNT_ARCHIVE_SCOPE) {
                stableIdGate.support(attempt) == true
            } else {
                roomFacts?.lease == copyRoomConsumerFacts(
                    entryGate, roomStableIdAuthorities, attempt, archiveJid,
                )?.lease
            }
            val fin = queryPage.mamFinIq
            val archiveRoom = if (request.scope == ACCOUNT_ARCHIVE_SCOPE) null else {
                requireCurrentArchiveRoom(roomFacts, copyRoomConsumerFacts(
                    entryGate, roomStableIdAuthorities, attempt, archiveJid,
                ), attempt, request.scope)
            }
            val rsm = requireNotNull(fin.rsmSet) { "MAM response omitted RSM boundaries" }
            val carriers = queryPage.mamResultCarrierMessages
            val results = queryPage.mamResultExtensions
            val messages = normalizeMamResults(
                carriers = carriers,
                results = results,
                attempt = attempt,
                expectedArchiveAuthority = archiveJid,
                mappingBareJid = expectedBareJid,
                trustStableIds = trustStableIds,
                ownRoomNick = roomFacts?.ownNick,
                archiveRoom = archiveRoom,
            )
            requireMamPageBoundaries(rsm.first, rsm.last, messages)
            synchronized(entryGate) {
                requireExactAttemptLocked(request.accountId, request.generation)
                require(connectionListener.currentAttempt() == attempt)
                if (request.scope != ACCOUNT_ARCHIVE_SCOPE) requireCurrentArchiveRoom(
                    roomFacts, copyRoomConsumerFacts(entryGate, roomStableIdAuthorities, attempt, archiveJid), attempt, request.scope,
                )
            }
            ArchivePageEnvelope(
                request = request,
                stable = fin.isStable,
                complete = fin.isComplete,
                hasEarlier = archiveHasEarlier(
                    direction = request.direction,
                    complete = fin.isComplete,
                    messageCount = messages.size,
                    firstIndex = rsm.firstIndex,
                ),
                firstId = rsm.first,
                lastId = rsm.last,
                messages = messages,
            )
        }

    override suspend fun loadVCard(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): RemoteVCardPayload = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        try {
            val jid = JidCreate.entityBareFrom(bareJid)
            val vcard = VCardManager.getInstanceFor(connection).loadVCard(jid)
            requireExactAttempt(accountId, generation)
            RemoteVCardPayload(
                formattedName = vcard.getField("FN"),
                nickname = vcard.nickName,
                photoBytes = vcard.avatar,
                photoMime = vcard.avatarMimeType,
                photoSha1 = vcard.avatarHash,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SendNotAttemptedException) {
            throw failure
        } catch (failure: Exception) {
            throwIfStale(accountId, generation)
            throw failure
        }
    }

    override suspend fun peerBlockingState(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): PeerBlockingState = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        try {
            val peer = JidCreate.entityBareFrom(bareJid)
            if (!isBlockingSupported(accountId, generation)) {
                requireExactAttempt(accountId, generation)
                return@runInterruptible PeerBlockingState(supported = false)
            }
            val blockedAddresses = queryBlockList(accountId, generation).blockingAddressesFor(peer)
            requireExactAttempt(accountId, generation)
            PeerBlockingState(
                supported = true,
                blockedAddresses = blockedAddresses.map(Jid::toString),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SendNotAttemptedException) {
            throw failure
        } catch (failure: Exception) {
            throwIfStale(accountId, generation)
            throw failure
        }
    }

    override suspend fun setPeerBlocked(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
        blocked: Boolean,
        entered: () -> Unit,
    ): PeerBlockingMutationResult = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        var commandEntered = false
        try {
            val peer = JidCreate.entityBareFrom(bareJid)
            if (!isBlockingSupported(accountId, generation)) {
                requireExactAttempt(accountId, generation)
                return@runInterruptible PeerBlockingMutationResult.NotAttempted
            }
            val before = queryBlockList(accountId, generation)
            val effectiveRules = before.blockingAddressesFor(peer)
            val command = when {
                blocked && effectiveRules.isEmpty() -> BlockContactsIQ(listOf(peer))
                !blocked && effectiveRules.isNotEmpty() -> UnblockContactsIQ(effectiveRules)
                else -> null
            }
            if (command == null) {
                requireExactAttempt(accountId, generation)
                return@runInterruptible PeerBlockingMutationResult.Confirmed(
                    PeerBlockingState(true, effectiveRules.map(Jid::toString)),
                )
            }
            when (sendBlockingCommand(accountId, generation, command) {
                commandEntered = true
                entered()
            }) {
                BlockingCommandResult.REJECTED -> return@runInterruptible PeerBlockingMutationResult.Rejected
                BlockingCommandResult.NOT_ATTEMPTED -> {
                    return@runInterruptible PeerBlockingMutationResult.NotAttempted
                }
                BlockingCommandResult.UNCERTAIN -> return@runInterruptible PeerBlockingMutationResult.Uncertain
                BlockingCommandResult.CONFIRMED -> Unit
            }
            val after = queryBlockList(accountId, generation)
            requireExactAttempt(accountId, generation)
            PeerBlockingMutationResult.Confirmed(
                PeerBlockingState(
                    supported = true,
                    blockedAddresses = after.blockingAddressesFor(peer).map(Jid::toString),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            if (commandEntered) {
                PeerBlockingMutationResult.Uncertain
            } else {
                PeerBlockingMutationResult.NotAttempted
            }
        }
    }

    override suspend fun fetchHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        url: String,
    ): ByteArray? {
        requireExactAttempt(accountId, generation)
        val bytes = httpTransfer.fetch(url)
        requireExactAttempt(accountId, generation)
        return bytes
    }

    override suspend fun uploadHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: LocalUploadRequest,
    ): UploadedFile? = runInterruptible(Dispatchers.IO) {
        request.requireValidSize()
        requireExactAttempt(accountId, generation)
        val manager = HttpFileUploadManager.getInstanceFor(connection)
        if (!manager.isUploadServiceDiscovered && !manager.discoverUploadService()) {
            return@runInterruptible null
        }
        requireExactAttempt(accountId, generation)
        val slot = manager.requestSlot(request.name, request.size, request.mime)
        requireExactAttempt(accountId, generation)
        val download = org.thanosapollo.nema.xmpp.httpupload.httpsAttachmentUrl(slot.getUrl.toString())
            ?: return@runInterruptible null
        if (!httpTransfer.put(slot.putUrl.toString(), slot.headers, request.bytes, request.mime)) return@runInterruptible null
        requireExactAttempt(accountId, generation)
        UploadedFile(
            url = download,
            name = request.name,
            mime = request.mime,
            size = request.size,
        )
    }

    override fun roomArchiveAuthorization(room: String): org.thanosapollo.nema.session.RoomArchiveAuthorization? =
        captureRoomArchiveAuthorization(entryGate, roomStableIdAuthorities, room) {
            connectionListener.currentAttempt().takeIf { !revoked.get() && isUsable }
        }

    override suspend fun joinMuc(
        accountId: AccountId,
        generation: ConnectionGeneration,
        roomJid: String,
        nick: String?,
        password: String?,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val attempt = requireNotNull(connectionListener.currentAttempt())
        val room = JidCreate.entityBareFrom(roomJid)
        val roomLease = roomStableIdAuthorities.beginJoin(attempt, room.toString())
            ?: throw SendNotAttemptedException()
        val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(room)
        var activated = false
        var candidateCurrent = { false }
        try {
        val roomFeatures = try {
            roomFeatureSupport(
                ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(room),
            )
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            RoomFeatureSupport(stableIds = false, occupantIds = false)
        }
        requireExactAttempt(accountId, generation)
        if (connectionListener.currentAttempt() != attempt) throw SendNotAttemptedException()
        val roomNick = preferredRoomNick(nick, expectedBareJid)
        val nickPart = Resourcepart.from(roomNick)
        if (!muc.isJoined) {
            val enter = muc.getEnterConfigurationBuilder(nickPart)
                .requestNoHistory()
                .let { builder ->
                    val secret = password?.trim().orEmpty()
                    if (secret.isEmpty()) builder else builder.withPassword(secret)
                }
                .build()
            try {
                muc.join(enter)
            } catch (_: MultiUserChatException.MucAlreadyJoinedException) {}
        }
        requireExactAttempt(accountId, generation)
        if (connectionListener.currentAttempt() != attempt || !muc.isJoined) throw SendNotAttemptedException()
        rememberRoomDiscoName(room, roomJid)
        val ownNick = muc.nickname?.toString() ?: roomNick
        val candidate = roomViewCandidate(
            muc, roomJid, roomLease, attempt, roomFeatures, ownNick, roomDiscoNames[roomJid],
        )
        candidateCurrent = candidate::isActive
        activated = candidate.activate()
        if (!activated) throw SendNotAttemptedException()
        true
        } finally {
            if (!activated && !candidateCurrent()) synchronized(entryGate) { roomStableIdAuthorities.revoke(roomLease) }
        }
    }

    override suspend fun bookmarkedRooms(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): List<String> = bookmarkedRoomDetails(accountId, generation).bookmarks.map(RoomBookmark::roomJid)

    override suspend fun bookmarkedRoomDetails(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): RoomBookmarkSnapshot = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val pep = pepBookmarks()
        val privateStorage = privateStorageBookmarks()
        RoomBookmarkSnapshot(
            bookmarks = mergeRoomBookmarks(
                pep.getOrDefault(emptyList()),
                privateStorage.getOrDefault(emptyList()),
            ),
            complete = pep.isSuccess && privateStorage.isSuccess,
        )
    }

    override suspend fun publishRoomBookmark(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bookmark: RoomBookmark,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        runCatching {
            val node = PubSubManager.getInstanceFor(connection, JidCreate.entityBareFrom(expectedBareJid))
                .getOrCreateLeafNode(BOOKMARKS2_NODE)
            node.publish(
                PayloadItem(
                    bookmark.roomJid,
                    SimplePayload(bookmark2Xml(bookmark)),
                ),
            )
            true
        }.getOrDefault(false)
    }

    private fun pepBookmarks(): Result<List<RoomBookmark>> = try {
        val node = PubSubManager.getInstanceFor(connection, JidCreate.entityBareFrom(expectedBareJid))
            .getLeafNode(BOOKMARKS2_NODE)
        Result.success(
            parseBookmark2Conferences(
                node.getItems<Item>().map { item ->
                    Bookmark2Item(
                        id = item.id,
                        payloadXml = (item as? PayloadItem<*>)?.payload?.toXML()?.toString(),
                    )
                },
            ),
        )
    } catch (error: XMPPErrorException) {
        if (error.stanzaError.condition == StanzaError.Condition.item_not_found) {
            Result.success(emptyList())
        } else {
            Result.failure(error)
        }
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun privateStorageBookmarks(): Result<List<RoomBookmark>> {
        val fromManager = runCatching {
            BookmarkManager.getBookmarkManager(connection)
                .bookmarkedConferences
                .mapNotNull { conference ->
                    val roomJid = conference.jid?.asBareJid()?.toString()?.takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    RoomBookmark(
                        roomJid = roomJid,
                        name = conference.name,
                        nick = conference.nickname?.toString(),
                        password = conference.password,
                        autojoin = conference.isAutoJoin,
                    )
                }
        }
        val fromXml = runCatching {
            val data = PrivateDataManager.getInstanceFor(connection)
                .getPrivateData("storage", STORAGE_BOOKMARKS_NAMESPACE)
            parseStorageBookmarks(data.toXML().toString())
        }
        if (fromManager.isFailure && fromXml.isFailure) {
            return Result.failure(fromManager.exceptionOrNull() ?: fromXml.exceptionOrNull()!!)
        }
        return Result.success(
            mergeRoomBookmarks(
                fromManager.getOrDefault(emptyList()),
                fromXml.getOrDefault(emptyList()),
            ),
        )
    }

    private fun rememberRoomDiscoName(room: EntityBareJid, roomJid: String) {
        if (roomDiscoNames.containsKey(roomJid)) return
        runCatching {
            MultiUserChatManager.getInstanceFor(connection)
                .getRoomInfo(room)
                .name
                ?.takeIf(String::isNotEmpty)
                ?.let { roomDiscoNames[roomJid] = it }
        }
    }

    private fun roomViewCandidate(
        muc: MultiUserChat, roomJid: String, lease: RoomStableIdLease,
        attempt: SessionAttemptIdentity, features: RoomFeatureSupport,
        ownNick: String, discoName: String?,
    ) = roomViewHandoff.candidate(
        lease = lease,
        mucMonitor = muc,
        joined = { !revoked.get() && connectionListener.currentAttempt() == attempt && isUsable && muc.isJoined },
        listeners = RoomViewHandoff.Listeners(
            status = { refresh, revoke -> RoomViewStatusListener(refresh, revoke) },
            participant = { refresh -> PresenceListener { refresh() } },
            subject = { refresh -> SubjectUpdatedListener { _, _ -> refresh() } },
            addStatus = muc::addUserStatusListener,
            addParticipant = muc::addParticipantListener,
            addSubject = muc::addSubjectUpdatedListener,
            removeStatus = { muc.removeUserStatusListener(it) },
            removeParticipant = { muc.removeParticipantListener(it) },
            removeSubject = { muc.removeSubjectUpdatedListener(it) },
        ),
        buildView = {
            val occupants = muc.occupants.mapNotNull { occupantJid ->
            val occupant = muc.getOccupant(occupantJid) ?: return@mapNotNull null
            occupant.toRoomOccupant()
            }
            SessionEvent.RoomUpdated(
                attempt,
                RoomView(
                    roomJid = roomJid,
                    subject = muc.subject?.takeIf(String::isNotEmpty),
                    discoName = discoName,
                    occupants = occupants,
                    ownNick = ownNick,
                ),
            )
        },
        deliver = event,
        stableIds = features.stableIds,
        occupantIds = features.occupantIds,
        mamV2 = features.mamV2,
        ownNick = ownNick,
    )

    private fun isBlockingSupported(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): Boolean {
        BlockingCommandManager.getInstanceFor(connection)
        val request = blockingSupportRequest(connection)
        return sendExactIq<DiscoverInfo>(accountId, generation, request)
            .containsFeature(BlockingCommandManager.NAMESPACE)
    }

    private fun queryBlockList(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): List<Jid> = sendExactIq<BlockListIQ>(accountId, generation, BlockListIQ()).blockedJidsCopy

    override suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope): ThreadDirectorySnapshot {
        requireExactAttempt(accountId, generation)
        return directoryClient(accountId, generation).list(directory).also { requireExactAttempt(accountId, generation) }
    }

    override suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction): ThreadDirectoryMutationResult {
        requireExactAttempt(accountId, generation)
        val client = directoryClient(accountId, generation)
        val result = when {
            action.revision == 0L -> client.create(action.context.scope, action.threadId, requireNotNull(action.title), action.operationId)
            action.title != null -> client.rename(action.context.scope, action.threadId, action.revision, action.title, action.operationId)
            else -> client.archive(action.context.scope, action.threadId, action.revision, requireNotNull(action.archived), action.operationId)
        }
        requireExactAttempt(accountId, generation)
        return result
    }

    private fun directoryClient(accountId: AccountId, generation: ConnectionGeneration) = SmackThreadDirectoryClient(connection) { request ->
        val collector = connection.createStanzaCollector(IQReplyFilter(request, connection))
        try {
            synchronized(entryGate) {
                requireExactAttemptLocked(accountId, generation)
                connection.sendStanza(request)
            }
            collector
        } catch (failure: Exception) {
            collector.cancel()
            throw failure
        }
    }

    private fun <T : IQ> sendExactIq(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: IQ,
    ): T {
        val collector = connection.createStanzaCollector(IQReplyFilter(request, connection))
        return try {
            synchronized(entryGate) {
                requireExactAttemptLocked(accountId, generation)
                connection.sendStanza(request)
            }
            collector.nextResultOrThrow()
        } finally {
            collector.cancel()
        }
    }

    private fun sendBlockingCommand(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: IQ,
        entered: () -> Unit,
    ): BlockingCommandResult {
        val collector = connection.createStanzaCollector(IQReplyFilter(request, connection))
        var enteredTransport = false
        return try {
            synchronized(entryGate) {
                requireExactAttemptLocked(accountId, generation)
                enteredTransport = true
                entered()
                connection.sendStanza(request)
            }
            collector.nextResultOrThrow<IQ>()
            BlockingCommandResult.CONFIRMED
        } catch (_: XMPPErrorException) {
            BlockingCommandResult.REJECTED
        } catch (_: SendNotAttemptedException) {
            BlockingCommandResult.NOT_ATTEMPTED
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            if (enteredTransport) BlockingCommandResult.UNCERTAIN else BlockingCommandResult.NOT_ATTEMPTED
        } finally {
            collector.cancel()
        }
    }

    override suspend fun disconnect() = runInterruptible(Dispatchers.IO) {
        if (!disconnectStarted.compareAndSet(false, true)) return@runInterruptible
        revoke()
        connectionListener.localDisconnect()
        connection.removeConnectionListener(connectionListener)
        connection.removeStanzaListener(messageListener)
        ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
        runCatching { synchronized(connection) { connection.disconnect() } }
        Unit
    }

    private fun requireExactAttempt(accountId: AccountId, generation: ConnectionGeneration) {
        synchronized(entryGate) {
            requireExactAttemptLocked(accountId, generation)
        }
    }

    private fun requireExactAttemptLocked(accountId: AccountId, generation: ConnectionGeneration) {
        val attempt = connectionListener.currentAttempt()
        if (revoked.get() || attempt?.accountId != accountId || attempt.generation != generation || !isUsable) {
            throw SendNotAttemptedException()
        }
    }

    private fun throwIfStale(accountId: AccountId, generation: ConnectionGeneration) {
        if (!isUsable ||
            connectionListener.currentAttempt()?.accountId != accountId ||
            connectionListener.currentAttempt()?.generation != generation
        ) {
            throw SendNotAttemptedException()
        }
    }

    private fun deliver(decision: StableIdMessageDecision) {
        // Durable callbacks can synchronously await IO sends or controller shutdown.
        // Capture admission once; never hold the transport monitor across that callback.
        admitIncoming(decision)?.let(event)
    }

    private fun admitIncoming(decision: StableIdMessageDecision): SessionEvent.Incoming? = synchronized(entryGate) {
        if (revoked.get() || connectionListener.currentAttempt() != decision.attempt) return@synchronized null
        val room = if (decision.carbonDirection == CarbonCarrier.Direction.SENT &&
            decision.message.type == Message.Type.groupchat
        ) decision.message.to?.asBareJid()?.toString() else decision.message.from?.asBareJid()?.toString()
        val roomFacts = copyRoomConsumerFacts(
            entryGate, roomStableIdAuthorities, decision.attempt, room,
        )
        val trustedStableIdAuthority = if (decision.message.type == Message.Type.groupchat) {
            roomFacts?.stableIdAuthority
        } else {
            expectedBareJid.takeIf { decision.trustStableIds }
        }
        decision.message.toIncomingEnvelope(
            decision.attempt,
            expectedBareJid,
            trustedStableIdAuthority,
            roomFacts?.ownNick,
            decision.sentAtEpochMs,
            decision.sentTimeSource,
            decision.receivedAtEpochMs,
            decision.carbonDirection,
        )?.let { envelope ->
            val admitted = decision.retainLiveMucFacts(envelope, roomFacts,
                connectionListener.currentAttempt(), "$mucOrderInstance:${decision.attempt}")
            admitted?.let { SessionEvent.Incoming(decision.attempt, it) }
        }
    }

    private suspend fun establish(
        attempt: SessionAttemptIdentity,
        credential: CharArray?,
    ) = runInterruptible(Dispatchers.IO) {
        connectionListener.attemptStarting(attempt)
        try {
            if (shouldResetSmackTransport(
                    connected = connection.isConnected,
                    authenticated = connection.isAuthenticated,
                )
            ) {
                connectionListener.localDisconnect()
                runCatching { connection.disconnect() }
                connectionListener.attemptStarting(attempt)
            }
            synchronized(connection) {
                ensureNotRevoked()
                if (connection is OnionXmppConnection) connection.beginAttempt(attempt)
                else torSockets?.beginAttempt(attempt)
                connection.connect()
            }
            if (!connection.isSecureConnection &&
                (connection !is OnionXmppConnection || !connection.permitsTransport(attempt))) {
                connectionListener.localDisconnect()
                runCatching { connection.disconnect() }
                throw SessionFailure(SessionFailureReason.TLS_CERTIFICATE)
            }
            synchronized(connection) {
                ensureNotRevoked()
                if (connection is OnionXmppConnection && !connection.permitsTransport(attempt)) {
                    throw SessionFailure(SessionFailureReason.TLS_CERTIFICATE)
                }
                if (credential == null) {
                    connection.login()
                } else {
                    connection.login(authenticationId, credential.concatToString(), null)
                }
            }
            if (connection.user?.asBareJid()?.toString() != expectedBareJid) {
                connectionListener.localDisconnect()
                runCatching { connection.disconnect() }
                throw SessionFailure(SessionFailureReason.AUTHENTICATION)
            }
            val carbons = resolveCarbonCapability(CarbonManager.getInstanceFor(connection))
            synchronized(entryGate) {
                if (revoked.get() || connectionListener.currentAttempt() != attempt) {
                    throw CancellationException("Session attempt replaced")
                }
                carbonCapability = attempt to carbons
            }
            if (!rosterLifecycle.load(attempt, rosterHandoffFactory(attempt)) {
                    !revoked.get() && connectionListener.currentAttempt() == attempt
                }
            ) throw CancellationException("Session attempt replaced")
            if (isUsable) {
                connectionListener.connected()
            } else {
                connectionListener.attemptFailed(attempt)
            }
        } catch (failure: CancellationException) {
            torSockets?.invalidateAttempt()
            connectionListener.attemptFailed(attempt)
            throw failure
        } catch (failure: SessionFailure) {
            torSockets?.invalidateAttempt()
            connectionListener.attemptFailed(attempt)
            throw failure
        } catch (error: Exception) {
            torSockets?.invalidateAttempt()
            connectionListener.attemptFailed(attempt)
            val reason = classifySmackFailure(error)
            throw SessionFailure(
                if (reason == SessionFailureReason.NETWORK && torSockets?.routeFailed == true)
                    SessionFailureReason.TOR_UNAVAILABLE else reason,
                error,
            )
        }
    }

    private fun ensureNotRevoked() {
        if (revoked.get()) throw CancellationException("Session revoked")
    }

    private inner class AttemptConnectionListener : ConnectionListener {
        @Volatile
        private var attempt: SessionAttemptIdentity? = null
        private val lossNotifier = ConnectionLossNotifier({ isUsable }) { reason ->
            attempt?.let { event(SessionEvent.ConnectionLost(it, reason)) }
        }

        override fun connectionClosed() {
            rosterLifecycle.retireCurrent()
            lossNotifier.remoteClosed(transportFailureReason(SessionFailureReason.NETWORK))
        }

        override fun connectionClosedOnError(error: Exception) {
            rosterLifecycle.retireCurrent()
            lossNotifier.remoteClosed(transportFailureReason(classifySmackFailure(error)))
        }

        private fun transportFailureReason(reason: SessionFailureReason): SessionFailureReason =
            if (connection is OnionXmppConnection && connection.hasIncompleteTlsNegotiation)
                SessionFailureReason.TLS_CERTIFICATE else reason

        fun attemptStarting(attempt: SessionAttemptIdentity) {
            rosterLifecycle.retireCurrent()
            roomViewHandoff.beginAttemptIf(attempt) {
                if (revoked.get()) return@beginAttemptIf false
                carbonCapability = null
                stableIdGate.begin(attempt)
                this.attempt = attempt
                lossNotifier.attemptStarting()
                true
            }
        }

        fun updateAttempt(attempt: SessionAttemptIdentity) {
            rosterLifecycle.retireCurrent()
            roomViewHandoff.beginAttemptIf(attempt) {
                if (revoked.get()) return@beginAttemptIf false
                carbonCapability = null
                stableIdGate.begin(attempt)
                this.attempt = attempt
                true
            }
        }

        fun currentAttempt(): SessionAttemptIdentity? = attempt

        fun attemptFailed(attempt: SessionAttemptIdentity) {
            rosterLifecycle.retire(attempt)
            lossNotifier.attemptFailed()
        }

        fun connected() = lossNotifier.connected()

        fun localDisconnect() {
            rosterLifecycle.retireCurrent()
            lossNotifier.localDisconnect()
        }
    }

}

internal data class RoomConsumerFacts(
    val lease: RoomStableIdLease, val stableIdAuthority: String?,
    val occupantIds: Boolean, val ownNick: String?,
    val mamV2: Boolean = false,
)

internal fun copyRoomConsumerFacts(
    entryGate: Any, registry: RoomStableIdAuthorityRegistry,
    attempt: SessionAttemptIdentity, authority: String?,
): RoomConsumerFacts? = synchronized(entryGate) {
    val snapshot = authority?.let { registry.snapshot(attempt, it) } ?: return@synchronized null
    RoomConsumerFacts(snapshot.lease, snapshot.lease.authority.takeIf { snapshot.stableIds }, snapshot.occupantIds, snapshot.ownNick, snapshot.mamV2)
}

internal fun captureRoomArchiveAuthorization(
    entryGate: Any, registry: RoomStableIdAuthorityRegistry, room: String,
    currentAttempt: () -> SessionAttemptIdentity?,
): org.thanosapollo.nema.session.RoomArchiveAuthorization? = synchronized(entryGate) {
    val attempt = currentAttempt() ?: return@synchronized null
    val captured = registry.snapshot(attempt, room)?.takeIf { it.mamV2 } ?: return@synchronized null
    org.thanosapollo.nema.session.RoomArchiveAuthorization(attempt, captured.lease.authority, captured.stableIds) {
        synchronized(entryGate) {
            currentAttempt() == attempt && registry.snapshot(attempt, room)?.let {
                it.lease == captured.lease && it.mamV2
            } == true
        }
    }
}

internal fun requireCurrentArchiveRoom(
    captured: RoomConsumerFacts?, current: RoomConsumerFacts?,
    attempt: SessionAttemptIdentity, scope: String,
): RoomConsumerFacts? {
    require(captured?.lease == current?.lease) { "Room archive lease changed" }
    require(captured == null || captured.lease.attempt == attempt && captured.lease.authority == scope) {
        "Room archive ownership changed"
    }
    return current?.takeIf { it.mamV2 }
}

private class RoomViewStatusListener(
    private val refresh: () -> Unit,
    private val revoke: () -> Unit,
) : UserStatusListener {
    override fun kicked(actor: Jid?, reason: String?) = revoke()
    override fun voiceGranted() = refresh()
    override fun voiceRevoked() = refresh()
    override fun banned(actor: Jid?, reason: String?) = revoke()
    override fun removed(mucUser: org.jivesoftware.smackx.muc.packet.MUCUser, presence: org.jivesoftware.smack.packet.Presence) = revoke()
    override fun membershipGranted() = refresh()
    override fun membershipRevoked() = revoke()
    override fun moderatorGranted() = refresh()
    override fun moderatorRevoked() = refresh()
    override fun ownershipGranted() = refresh()
    override fun ownershipRevoked() = refresh()
    override fun adminGranted() = refresh()
    override fun adminRevoked() = refresh()
    override fun roomDestroyed(multiUserChat: MultiUserChat, reason: String?) = revoke()
}

internal fun roomFeatureSupport(info: DiscoverInfo) = RoomFeatureSupport(
    stableIds = info.containsFeature(StableUniqueStanzaIdManager.NAMESPACE),
    occupantIds = info.containsFeature(OCCUPANT_ID_NAMESPACE),
    mamV2 = info.containsFeature("urn:xmpp:mam:2"),
)

private enum class BlockingCommandResult { CONFIRMED, REJECTED, NOT_ATTEMPTED, UNCERTAIN }

internal fun blockingSupportRequest(connection: XMPPConnection): DiscoverInfo =
    DiscoverInfo.builder(connection)
        .to(connection.xmppServiceDomain)
        .build()

internal fun List<Jid>.blockingAddressesFor(peer: Jid): List<Jid> =
    filter { blocked -> blocked == peer || blocked == peer.domain }.distinct()

internal fun classifySmackFailure(error: Exception): SessionFailureReason {
    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
    return when {
        causes.any { it is NemaProtocolParsingException } -> SessionFailureReason.PROTOCOL
        causes.any {
            it is SSLException ||
                it is CertificateException ||
                it is SmackException.SecurityRequiredException
        } -> SessionFailureReason.TLS_CERTIFICATE
        causes.any { it is SASLErrorException } -> SessionFailureReason.AUTHENTICATION
        causes.any { it is IOException || it is SmackException } -> SessionFailureReason.NETWORK
        else -> SessionFailureReason.CONFIGURATION
    }
}

internal fun shouldResetSmackTransport(
    connected: Boolean,
    authenticated: Boolean,
): Boolean = connected && !authenticated

internal fun OutgoingReactionEnvelope.toSmackReaction(): Message = StanzaBuilder.buildMessage()
    .to(JidCreate.entityBareFrom(recipient))
    .ofType(if (messageKind == MessageKind.GROUPCHAT) Message.Type.groupchat else Message.Type.chat)
    .addReactions(targetId, emojis)
    .build()

internal fun reactionSendAuthorized(
    reaction: OutgoingReactionEnvelope,
    attempt: SessionAttemptIdentity,
    roomLease: RoomStableIdLease?,
    roomAuthorities: RoomStableIdAuthorityRegistry,
): Boolean = reaction.accountId == attempt.accountId &&
    reaction.generation == attempt.generation &&
    (reaction.messageKind == MessageKind.CHAT ||
        (roomLease?.attempt == attempt && roomLease.authority == reaction.recipient &&
            roomAuthorities.isCurrent(roomLease)))

internal fun OutgoingMessageSignal.toSmackMessage(): Message {
    val builder = StanzaBuilder.buildMessage()
        .to(JidCreate.from(recipient))
        .ofType(Message.Type.chat)
    val extension = when (protocol) {
        MessageSignalProtocol.DELIVERY_RECEIPT -> DeliveryReceipt(targetId)
        MessageSignalProtocol.CHAT_MARKER -> StandardExtensionElement
            .builder(stage.name.lowercase(), CHAT_MARKERS_NAMESPACE)
            .addAttribute("id", targetId)
            .build()
    }
    return builder.addExtension(extension).build()
}

internal fun OutgoingMessageEnvelope.toSmackMessage(): Message {
    val builder = StanzaBuilder.buildMessage(operationId)
        .to(JidCreate.entityBareFrom(recipient))
        .ofType(if (kind == MessageKind.GROUPCHAT) Message.Type.groupchat else Message.Type.chat)
        .addExtension(OriginIdElement(originId))
    reply?.let { builder.addReply(it, body) } ?: builder.setBody(body)
    thread?.let(builder::setThreadRef)
    attachmentUrl?.let { url ->
        builder.addExtension(
            oobExtension(
                OutOfBandShare(
                    url = url,
                    description = attachmentName,
                ),
            ),
        )
    }
    if (kind == MessageKind.CHAT) {
        replaceId?.let { builder.addExtension(MessageCorrectExtension(it)) }
        DeliveryReceiptRequest.addTo(builder)
        builder.addMarkable()
        builder.addChatState(ChatActivity.ACTIVE)
    }
    return builder.build()
}

internal fun org.thanosapollo.nema.xmpp.transport.OutgoingChatState.toSmackMessage(): Message =
    StanzaBuilder.buildMessage()
        .to(JidCreate.entityBareFrom(recipient))
        .ofType(Message.Type.chat)
        .addChatState(activity)
        .build()

internal fun Message.toIncomingSignal(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
): IncomingMessageSignal? {
    if (type != Message.Type.chat || body != null) return null
    val sender = from?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    val recipient = to?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    val ownDisplayed = sender == expectedBareJid && recipient != expectedBareJid
    val peerReceipt = sender != expectedBareJid && recipient == expectedBareJid
    if (!ownDisplayed && !peerReceipt) return null
    val signals = extensions.mapNotNull { extension ->
        when {
            extension.elementName == RECEIVED_ELEMENT && extension.namespace == RECEIPTS_NAMESPACE ->
                (extension as? DeliveryReceipt)?.id?.let {
                    Triple(MessageReceiptStage.RECEIVED, MessageSignalProtocol.DELIVERY_RECEIPT, it)
                }
            extension.namespace == CHAT_MARKERS_NAMESPACE && extension is StandardExtensionElement -> {
                val stage = when (extension.elementName) {
                    RECEIVED_ELEMENT -> MessageReceiptStage.RECEIVED
                    DISPLAYED_ELEMENT -> MessageReceiptStage.DISPLAYED
                    ACKNOWLEDGED_ELEMENT -> MessageReceiptStage.ACKNOWLEDGED
                    else -> null
                }
                stage?.let {
                    Triple(it, MessageSignalProtocol.CHAT_MARKER, extension.getAttributeValue("id"))
                }
            }
            else -> null
        }
    }
    if (signals.size != 1) return null
    val (stage, protocol, targetId) = signals.single()
    if (targetId.isNullOrEmpty()) return null
    if (ownDisplayed &&
        (stage != MessageReceiptStage.DISPLAYED || protocol != MessageSignalProtocol.CHAT_MARKER)
    ) {
        return null
    }
    return IncomingMessageSignal(
        accountId = attempt.accountId,
        generation = attempt.generation,
        peer = if (ownDisplayed) recipient else sender,
        sender = sender,
        targetId = targetId,
        stage = stage,
        protocol = protocol,
    )
}

internal fun Message.toIncomingChatState(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    ownRoomNick: String? = null,
    ownSenderPeer: String? = null,
): IncomingChatState? {
    if (type != Message.Type.chat && type != Message.Type.groupchat && type != Message.Type.normal) return null
    val fromJid = from ?: return null
    val activities = extensions.mapNotNull { extension ->
        if (extension.namespace == CHAT_STATES_NAMESPACE) {
            chatActivityNamed(extension.elementName)
        } else {
            null
        }
    }
    val activity = activities.singleOrNull() ?: return null
    return if (type == Message.Type.groupchat) {
        val room = fromJid.asBareJid().takeIf { it.isEntityBareJid }?.toString() ?: return null
        val nick = fromJid.resourceOrNull?.toString()?.takeIf(String::isNotEmpty) ?: return null
        if (nick == ownRoomNick) return null
        IncomingChatState(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = room,
            actor = nick,
            groupChat = true,
            activity = activity,
        )
    } else {
        val sender = fromJid.asBareJid().takeIf { it.isEntityBareJid }?.toString() ?: return null
        val peer = if (sender == expectedBareJid) ownSenderPeer ?: return null else sender
        IncomingChatState(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = peer,
            actor = sender,
            groupChat = false,
            activity = activity,
        )
    }
}

internal fun MessageCarrier.toIncomingChatState(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    ownRoomNick: String? = null,
): IncomingChatState? {
    return when (this) {
        is MessageCarrier.Direct -> message.toIncomingChatState(attempt, expectedBareJid, ownRoomNick)
        is MessageCarrier.Carbon -> {
            if (!message.body.isNullOrEmpty()) return null
            val ownSenderPeer = if (direction == CarbonCarrier.Direction.SENT) {
                message.to?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
            } else {
                null
            }
            message.toIncomingChatState(attempt, expectedBareJid, ownRoomNick, ownSenderPeer)
        }
        MessageCarrier.Inert -> null
    }
}

internal fun Message.encryptedMessagePlaceholder(): String? {
    val encrypted = extensions.any { extension ->
        val element = extension as? StandardExtensionElement ?: return@any false
        val namespace = element.namespace.orEmpty()
        when {
            element.elementName == "encrypted" &&
                (namespace == "eu.siacs.conversations.axolotl" || namespace.startsWith("urn:xmpp:omemo:")) ->
                !element.getFirstElement("payload", namespace)?.text.isNullOrEmpty()
            element.elementName == "x" && namespace == "jabber:x:encrypted" ->
                !element.text.isNullOrEmpty()
            else -> false
        }
    }
    return "Encrypted message".takeIf { encrypted }
}

internal fun Message.toIncomingRtt(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
): IncomingRealTimeText? {
    if (type != Message.Type.chat && type != Message.Type.normal) return null
    val parsed = parseRtt()
    val hasBody = !body.isNullOrEmpty()
    if (parsed == null && !hasBody) return null
    val sender = from?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    if (sender == expectedBareJid) return null
    return IncomingRealTimeText(
        accountId = attempt.accountId,
        generation = attempt.generation,
        peer = sender,
        element = parsed,
        hasBody = hasBody,
    )
}

internal fun Message.toIncomingReaction(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    liveCarrier: Boolean = true,
    roomFacts: RoomConsumerFacts? = null,
): IncomingReactionEnvelope? {
    val parsed = parseReactions() ?: return null
    if (type == Message.Type.groupchat) {
        if (!liveCarrier || DelayInformation.from(this) != null) return null
        val fromJid = from?.takeIf { it.isEntityFullJid } ?: return null
        val room = fromJid.asBareJid().toString()
        val nick = fromJid.resourceOrNull?.toString()?.takeIf(String::isNotEmpty) ?: return null
        val actor = liveMucActor(attempt, room, nick, roomFacts)
            ?: return null
        return IncomingReactionEnvelope(
            attempt.accountId, attempt.generation, expectedBareJid, room, room,
            parsed.targetId, parsed.emojis, actor = actor, messageKind = MessageKind.GROUPCHAT,
        )
    }
    if (type != Message.Type.chat) return null
    val fromBare = from?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    val toBare = to?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString()
    val peer = when {
        fromBare == expectedBareJid -> toBare ?: return null
        toBare == null || toBare == expectedBareJid -> fromBare
        else -> return null
    }
    return IncomingReactionEnvelope(
        accountId = attempt.accountId,
        generation = attempt.generation,
        accountBareJid = expectedBareJid,
        peer = peer,
        senderBareJid = fromBare,
        targetId = parsed.targetId,
        emojis = parsed.emojis,
        delayedAtMs = DelayInformation.from(this)?.stamp?.time,
    )
}

private fun Message.liveMucActor(
    attempt: SessionAttemptIdentity,
    room: String,
    nick: String,
    facts: RoomConsumerFacts?,
): ReactionActor? {
    if (facts?.lease?.attempt != attempt || facts.lease.authority != room ||
        facts.stableIdAuthority != room || !facts.occupantIds
    ) return null
    val ownNick = facts.ownNick ?: return null
    val occupant = nemaOccupantActor() ?: return null
    return if (nick == ownNick) ReactionActor.MucOwn else occupant
}

private fun Message.mucActorBareJid(): String? = extensions
    .filterIsInstance<NemaMucUser>()
    .singleOrNull()
    ?.takeIf { it.itemCount == 1 }
    ?.item
    ?.jid
    ?.asBareJid()
    ?.takeIf { it.isEntityBareJid }
    ?.toString()

internal fun Message.toIncomingEnvelope(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    trustedStableIdAuthority: String? = null,
    ownRoomNick: String? = null,
    suppliedSentAtEpochMs: Long? = null,
    suppliedSentTimeSource: MessageTimeSource? = null,
    receivedAtEpochMs: Long = System.currentTimeMillis(),
    carbonDirection: CarbonCarrier.Direction? = null,
): IncomingMessageEnvelope? {
    val topLevelDelay = DelayInformation.from(this)?.stamp?.time
    val sentAtEpochMs = (suppliedSentAtEpochMs ?: topLevelDelay)?.coerceAtMost(receivedAtEpochMs)
    val sentTimeSource = suppliedSentTimeSource ?: topLevelDelay?.let { MessageTimeSource.DELAYED }
    require((sentAtEpochMs == null) == (sentTimeSource == null)) {
        "Message time and provenance must be supplied together"
    }
    val groupChat = type == Message.Type.groupchat
    if (!groupChat && type != Message.Type.chat && type != Message.Type.normal) return null
    val fromJid = from ?: return null
    val receiptRequested = carbonDirection == null && type == Message.Type.chat &&
        extensions.count { it is DeliveryReceiptRequest } == 1
    val markable = type == Message.Type.chat && extensions.count {
        it.elementName == org.thanosapollo.nema.xmpp.markers.MARKABLE_ELEMENT &&
            it.namespace == CHAT_MARKERS_NAMESPACE && it is StandardExtensionElement
    } == 1
    val replaceId = if (type == Message.Type.chat) {
        extensions.filter {
            it.elementName == MessageCorrectExtension.ELEMENT &&
                it.namespace == MessageCorrectExtension.NAMESPACE
        }
            .singleOrNull()
            ?.let { it as? MessageCorrectExtension }
            ?.let { if (it is NemaCorrectionElement) it.wireId else it.idInitialMessage }
            ?.takeIf(String::isNotEmpty)
    } else {
        null
    }
    val share = oobShare()
    val reply = replyReference()
    val parsed = if (reply == null) null else parseReplyBody()
    val messageBody = if (parsed == null) {
        body?.takeIf(String::isNotEmpty) ?: share?.url ?: encryptedMessagePlaceholder()
    } else {
        parsed.body.takeIf(String::isNotEmpty) ?: share?.url ?: encryptedMessagePlaceholder()
    } ?: return null
    val replyEnvelope = reply?.copy(fallbackBody = parsed?.fallbackBody)
    return if (groupChat) {
        val carbonSent = carbonDirection == CarbonCarrier.Direction.SENT
        val roomJid = if (carbonSent) to else fromJid
        val room = roomJid?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
        val occupant = fromJid.takeIf { it.isEntityFullJid }?.toString() ?: room
        val occupantNick = fromJid.resourceOrNull?.toString()
        IncomingMessageEnvelope(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = room,
            sender = occupant,
            outbound = carbonSent || (occupantNick != null && occupantNick == ownRoomNick) ||
                (sentTimeSource == MessageTimeSource.MAM && mucActorBareJid() == expectedBareJid),
            originId = structurallyValidOriginId(),
            body = messageBody,
            thread = toThreadRef(),
            stanzaIds = trustedStanzaIds(trustedStableIdAuthority.takeIf { it == room }),
            kind = MessageKind.GROUPCHAT,
            attachmentUrl = share?.url,
            attachmentName = share?.description,
            reply = replyEnvelope,
            sentAtEpochMs = sentAtEpochMs,
            sentTimeSource = sentTimeSource,
        )
    } else {
        val sender = fromJid.asBareJid().takeIf { it.isEntityBareJid }?.toString() ?: return null
        val outbound = sender == expectedBareJid
        val peer = if (outbound) {
            to?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
        } else {
            sender
        }
        IncomingMessageEnvelope(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = peer,
            sender = sender,
            outbound = outbound,
            originId = structurallyValidOriginId(),
            messageId = stanzaId,
            body = messageBody,
            thread = toThreadRef(),
            stanzaIds = trustedStanzaIds(trustedStableIdAuthority.takeIf { it == expectedBareJid }),
            kind = MessageKind.CHAT,
            attachmentUrl = share?.url,
            attachmentName = share?.description,
            reply = replyEnvelope,
            sentAtEpochMs = sentAtEpochMs,
            sentTimeSource = sentTimeSource,
            receiptRequested = receiptRequested,
            receiptRecipient = fromJid.toString().takeIf { receiptRequested },
            markable = markable,
            replaceId = replaceId,
        )
    }
}

private fun Message.structurallyValidOriginId(): String? {
    val candidates = extensions.filter {
        it.elementName == OriginIdElement.ELEMENT && it.namespace == StableUniqueStanzaIdManager.NAMESPACE
    }
    val candidate = candidates.singleOrNull() as? OriginIdElement ?: return null
    if (candidate is NemaOriginIdElement && !candidate.structurallyValid) return null
    return candidate.id.takeIf(String::isNotEmpty)
}

private fun Message.trustedStanzaIds(authority: String?): List<StanzaIdEnvelope> {
    if (authority == null) return emptyList()
    val candidate = stanzaIdClaims(authority).singleOrNull() as? StanzaIdElement ?: return emptyList()
    if (candidate is NemaStanzaIdElement && !candidate.structurallyValid) return emptyList()
    if (candidate.id.isEmpty()) return emptyList()
    return listOf(StanzaIdEnvelope(candidate.id, candidate.by))
}

private fun Message.stanzaIdClaims(authority: String) = extensions.filter {
        it.elementName == StanzaIdElement.ELEMENT &&
            it.namespace == StableUniqueStanzaIdManager.NAMESPACE &&
            when (it) {
                is StanzaIdElement -> it.by == authority
                is StandardExtensionElement -> it.getAttributeValue("by") == authority
                else -> false
            }
    }


internal data class OutgoingFailureMapping(
    val consumed: Boolean,
    val failure: OutgoingFailureEnvelope?,
) {
    init {
        require(consumed || failure == null) { "Unconsumed stanza cannot carry a failure" }
    }
}

internal fun MessageCarrier.classifyOutgoingFailure(expectedBareJid: String): OutgoingFailureMapping = when (this) {
    is MessageCarrier.Direct -> if (message.type == Message.Type.error) {
        OutgoingFailureMapping(true, message.directFailure(expectedBareJid))
    } else {
        OutgoingFailureMapping(false, null)
    }
    is MessageCarrier.Carbon -> if (message.type == Message.Type.error) {
        OutgoingFailureMapping(true, message.directFailure(expectedBareJid).takeIf { direction == CarbonCarrier.Direction.RECEIVED })
    } else {
        OutgoingFailureMapping(false, null)
    }
    MessageCarrier.Inert -> OutgoingFailureMapping(true, null)
}

private fun Message.directFailure(expectedBareJid: String): OutgoingFailureEnvelope? {
    val operationId = stanzaId?.takeIf(String::isNotEmpty) ?: return null
    val sender = from?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    val recipient = to?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    val condition = error?.condition ?: return null
    if (sender == expectedBareJid || recipient != expectedBareJid) return null
    return OutgoingFailureEnvelope(operationId, sender, condition.toString())
}

internal data class TrustedIncomingStanza(
    val message: Message,
    val sentAtEpochMs: Long? = null,
    val sentTimeSource: MessageTimeSource? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val forwarded: Boolean = false,
    val carbonDirection: CarbonCarrier.Direction? = null,
)

internal fun TrustedIncomingStanza.isRawLive(mamCarrier: Boolean): Boolean =
    !forwarded && sentTimeSource == null && !mamCarrier

internal object CarbonCarrier {
    enum class Direction { SENT, RECEIVED }
}

internal sealed interface MessageCarrier {
    data class Direct(val message: Message) : MessageCarrier
    data class Carbon(
        val direction: CarbonCarrier.Direction,
        val message: Message,
        val delay: DelayInformation?,
    ) : MessageCarrier
    data object Inert : MessageCarrier
}

internal enum class BodylessCarbonEffect { SIGNAL, CHAT_STATE, RTT, REACTION }

internal fun MessageCarrier.bodylessCarbonEffect(): BodylessCarbonEffect? {
    val carbon = this as? MessageCarrier.Carbon ?: return null
    if (!carbon.message.body.isNullOrEmpty()) return null
    return carbon.message.extensions.mapNotNull { extension ->
        when (extension.namespace) {
            CHAT_STATES_NAMESPACE -> BodylessCarbonEffect.CHAT_STATE.takeIf {
                extension.elementName in setOf("active", "composing", "gone", "inactive", "paused")
            }
            RECEIPTS_NAMESPACE -> BodylessCarbonEffect.SIGNAL.takeIf {
                extension.elementName == "request" || extension.elementName == "received"
            }
            CHAT_MARKERS_NAMESPACE -> BodylessCarbonEffect.SIGNAL.takeIf {
                extension.elementName in setOf("acknowledged", "displayed", "markable", "received")
            }
            REACTIONS_NAMESPACE -> BodylessCarbonEffect.REACTION.takeIf { extension.elementName == "reactions" }
            RTT_NAMESPACE -> BodylessCarbonEffect.RTT.takeIf { extension.elementName == "rtt" }
            else -> null
        }
    }.singleOrNull()
}

internal fun Message.classifyCarrier(expectedBareJid: String, boundFullJid: String): MessageCarrier {
    val candidates = extensions.filter {
        it.namespace == CarbonExtension.NAMESPACE &&
            it.elementName in setOf("sent", "received")
    }
    if (candidates.isEmpty()) return MessageCarrier.Direct(this)
    if (candidates.size != 1) return MessageCarrier.Inert
    val carbon = candidates.single() as? CarbonExtension ?: return MessageCarrier.Inert
    if (from?.takeIf { it.isEntityBareJid }?.toString() != expectedBareJid) return MessageCarrier.Inert
    val direction = when (carbon.direction) {
        CarbonExtension.Direction.sent -> CarbonCarrier.Direction.SENT
        CarbonExtension.Direction.received -> CarbonCarrier.Direction.RECEIVED
    }
    if (carbon.elementName != direction.name.lowercase()) return MessageCarrier.Inert
    if (direction == CarbonCarrier.Direction.RECEIVED && to?.toString() != boundFullJid) return MessageCarrier.Inert
    val inner = carbon.forwarded.forwardedStanza as? Message ?: return MessageCarrier.Inert
    return MessageCarrier.Carbon(direction, inner, carbon.forwarded.delayInformation)
}

internal fun MessageCarrier.toTrustedCarbonMessage(
    expectedBareJid: String,
    receivedAtEpochMs: Long = System.currentTimeMillis(),
    joinedRoom: (String) -> Boolean = { false },
): TrustedIncomingStanza? {
    if (this is MessageCarrier.Inert) return null
    if (this is MessageCarrier.Direct) return TrustedIncomingStanza(message, receivedAtEpochMs = receivedAtEpochMs)
    this as MessageCarrier.Carbon
    val forwarded = message
    val sender = forwarded.from?.asBareJid()?.toString() ?: return null
    val recipient = forwarded.to?.asBareJid()?.toString() ?: return null
    if (!forwarded.hasCarbonPayload() || forwarded.type == Message.Type.headline) return null
    if (forwarded.extensions.any {
            (it.elementName == "x" && it.namespace == "http://jabber.org/protocol/muc#user") ||
                (it.elementName == "private" && it.namespace == CarbonExtension.NAMESPACE)
        }
    ) return null
    val trusted = when (direction) {
        CarbonCarrier.Direction.SENT -> forwarded.takeIf {
            sender == expectedBareJid &&
                (it.type == Message.Type.groupchat || !joinedRoom(recipient))
        }
        CarbonCarrier.Direction.RECEIVED -> forwarded.takeIf {
            recipient == expectedBareJid && it.type != Message.Type.groupchat &&
                !joinedRoom(sender)
        }
    } ?: return null
    return TrustedIncomingStanza(
        message = trusted,
        sentAtEpochMs = delay?.stamp?.time ?: receivedAtEpochMs,
        sentTimeSource = MessageTimeSource.CARBON,
        receivedAtEpochMs = receivedAtEpochMs,
        forwarded = true,
        carbonDirection = direction,
    )
}

private fun Message.hasCarbonPayload(): Boolean {
    val payloads = extensions.filterNot {
        it.namespace == "jabber:client" && it.elementName in setOf("body", "thread")
    }
    return !body.isNullOrEmpty() && payloads.all(::isCarbonPayload) ||
        body.isNullOrEmpty() && payloads.isNotEmpty() && payloads.all(::isCarbonPayload)
}

private fun isCarbonPayload(extension: org.jivesoftware.smack.packet.ExtensionElement): Boolean {
    val names = when (extension.namespace) {
        CHAT_STATES_NAMESPACE -> setOf("active", "composing", "gone", "inactive", "paused")
        RECEIPTS_NAMESPACE -> setOf("request", "received")
        CHAT_MARKERS_NAMESPACE -> setOf("acknowledged", "displayed", "markable", "received")
        REACTIONS_NAMESPACE -> setOf("reactions")
        RTT_NAMESPACE -> setOf("rtt")
        StableUniqueStanzaIdManager.NAMESPACE -> setOf("origin-id", "stanza-id")
        "urn:xmpp:reply:0" -> setOf("reply")
        "urn:xmpp:fallback:0" -> setOf("fallback")
        "urn:xmpp:hints" -> setOf("store", "no-store", "no-permanent-store", "no-copy")
        DelayInformation.NAMESPACE -> setOf("delay")
        "urn:xmpp:mam:tmp" -> setOf("archived")
        OCCUPANT_ID_NAMESPACE -> setOf("occupant-id")
        MessageCorrectExtension.NAMESPACE -> setOf("replace")
        "jabber:x:oob", "jabber:x:encrypted" -> setOf("x")
        "eu.siacs.conversations.axolotl" -> setOf("encrypted")
        else -> if (extension.namespace.startsWith("urn:xmpp:omemo:")) setOf("encrypted") else emptySet()
    }
    return extension.elementName in names
}

internal fun List<Message>.haveArchiveAuthority(expectedArchiveAuthority: String): Boolean = all {
    it.from?.asBareJid()?.toString() == expectedArchiveAuthority
}

internal fun normalizeMamResults(
    carriers: List<Message>,
    results: List<MamResultExtension>,
    attempt: SessionAttemptIdentity,
    expectedArchiveAuthority: String,
    trustStableIds: Boolean,
    mappingBareJid: String = expectedArchiveAuthority,
    ownRoomNick: String? = null,
    receivedAtEpochMs: Long = System.currentTimeMillis(),
    archiveRoom: RoomConsumerFacts? = null,
): List<ArchiveMessageEnvelope> {
    require(carriers.size == results.size) { "MAM result metadata does not match carriers" }
    require(carriers.haveArchiveAuthority(expectedArchiveAuthority)) {
        "MAM result source does not match archive authority"
    }
    if (archiveRoom != null) {
        require(archiveRoom.mamV2 && archiveRoom.lease.attempt == attempt &&
            archiveRoom.lease.authority == expectedArchiveAuthority && expectedArchiveAuthority != mappingBareJid)
        require(carriers.all { it.from?.toString() == expectedArchiveAuthority }) { "Room archive source is not bare" }
        // MamManager collected this page with its fresh MamResultFilter and authenticated final IQ.
        require(results.isEmpty() || results.first().queryId?.isNotEmpty() == true)
        require(results.map { it.queryId }.distinct().size <= 1)
        require(carriers.zip(results).all { (carrier, result) -> MamResultExtension.from(carrier) === result })
    }
    return results.map { result ->
        val owned = result as? NemaMamResultExtension
            ?: error("MAM result bypassed Nema normalization")
        val signal = owned.actualMessage
            ?.takeIf { expectedArchiveAuthority == mappingBareJid }
            ?.toIncomingSignal(attempt, mappingBareJid)
        var mappedMessage = if (signal == null) {
            owned.actualMessage?.toIncomingEnvelope(
                attempt,
                mappingBareJid,
                expectedArchiveAuthority.takeIf { trustStableIds },
                ownRoomNick,
                owned.forwarded.delayInformation?.stamp?.time,
                owned.forwarded.delayInformation?.let { MessageTimeSource.MAM },
                receivedAtEpochMs,
            )
        } else {
            null
        }
        if (archiveRoom != null && owned.actualMessage != null) {
            require(owned.id.isNotEmpty())
            val inner = requireNotNull(owned.actualMessage)
            require(inner.type == Message.Type.groupchat && inner.from?.asBareJid()?.toString() == expectedArchiveAuthority)
            require(mappedMessage == null || mappedMessage.kind == MessageKind.GROUPCHAT && mappedMessage.peer == expectedArchiveAuthority)
            val claims = inner.stanzaIdClaims(expectedArchiveAuthority)
            val identity = StanzaIdEnvelope(owned.id, expectedArchiveAuthority)
            require(claims.isEmpty() || claims.size == 1 && inner.trustedStanzaIds(expectedArchiveAuthority) == listOf(identity)) {
                "Room archive UID contradicts inner stanza identity"
            }
            val facts = inner.mucEventFacts(attempt, archiveRoom, org.thanosapollo.nema.storage.MucOccupantEvidence.ROOM_MAM)
            mappedMessage = mappedMessage?.copy(stanzaIds = listOf(identity), mucFacts = facts, messageId = facts?.messageId)
        }
        ArchiveMessageEnvelope(
            resultId = owned.id,
            message = mappedMessage?.takeIf {
                expectedArchiveAuthority == mappingBareJid || it.kind == MessageKind.GROUPCHAT
            },
            signal = signal,
        )
    }
}

internal fun requireMamPageBoundaries(
    firstId: String?,
    lastId: String?,
    messages: List<ArchiveMessageEnvelope>,
) {
    require(
        if (messages.isEmpty()) {
            firstId == null && lastId == null
        } else {
            firstId == messages.first().resultId && lastId == messages.last().resultId
        },
    ) { "MAM response boundaries do not match full result sequence" }
}

internal data class StableIdMessageDecision(
    val attempt: SessionAttemptIdentity,
    val message: Message,
    val trustStableIds: Boolean,
    val sentAtEpochMs: Long? = null,
    val sentTimeSource: MessageTimeSource? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val carbonDirection: CarbonCarrier.Direction? = null,
)

internal class StableIdDiscoveryGate {
    private var attempt: SessionAttemptIdentity? = null
    private var state: State = State.Unknown
    private val pending = ArrayDeque<TrustedIncomingStanza>()

    @Synchronized
    fun begin(attempt: SessionAttemptIdentity) {
        this.attempt = attempt
        state = State.Unknown
        pending.clear()
    }

    @Synchronized
    fun accept(attempt: SessionAttemptIdentity, message: Message): List<StableIdMessageDecision> =
        accept(attempt, TrustedIncomingStanza(message))

    @Synchronized
    fun accept(attempt: SessionAttemptIdentity, message: TrustedIncomingStanza): List<StableIdMessageDecision> {
        if (this.attempt != attempt) return emptyList()
        return when (val current = state) {
            State.Unknown, is State.Draining -> listOf(message.toDecision(attempt, supported = false))
            is State.Open -> listOf(
                message.toDecision(attempt, current.supported),
            )
        }
    }

    @Synchronized
    fun complete(attempt: SessionAttemptIdentity, supported: Boolean): List<StableIdMessageDecision> {
        if (this.attempt != attempt) return emptyList()
        return when (state) {
            State.Unknown -> {
                state = State.Draining(supported)
                takePending(attempt, supported)
            }
            // Capability retries must not retire live delivery or change identities already
            // accepted under this attempt. The first decision (including fallback) is final.
            is State.Draining, is State.Open -> emptyList()
        }
    }

    @Synchronized
    fun drain(attempt: SessionAttemptIdentity): List<StableIdMessageDecision> {
        val draining = state as? State.Draining
        if (this.attempt != attempt || draining == null) return emptyList()
        val decision = draining.supported
        if (pending.isNotEmpty()) return takePending(attempt, decision)
        state = State.Open(decision)
        return emptyList()
    }

    @Synchronized
    fun support(attempt: SessionAttemptIdentity): Boolean? {
        if (this.attempt != attempt) return null
        return when (val current = state) {
            State.Unknown -> null
            is State.Draining -> current.supported
            is State.Open -> current.supported
        }
    }

    @Synchronized
    fun retire(attempt: SessionAttemptIdentity) {
        if (this.attempt != attempt) return
        retireAll()
    }

    @Synchronized
    fun retireAll() {
        attempt = null
        state = State.Unknown
        pending.clear()
    }

    private fun takePending(
        attempt: SessionAttemptIdentity,
        supported: Boolean,
    ): List<StableIdMessageDecision> = buildList(pending.size) {
        while (pending.isNotEmpty()) {
            add(pending.removeFirst().toDecision(attempt, supported))
        }
    }

    private sealed interface State {
        data object Unknown : State
        data class Draining(val supported: Boolean) : State
        data class Open(val supported: Boolean) : State
    }
}

private fun TrustedIncomingStanza.toDecision(
    attempt: SessionAttemptIdentity,
    supported: Boolean,
) = StableIdMessageDecision(
    attempt = attempt,
    message = message,
    trustStableIds = supported,
    sentAtEpochMs = sentAtEpochMs,
    sentTimeSource = sentTimeSource,
    receivedAtEpochMs = receivedAtEpochMs,
    carbonDirection = carbonDirection,
)

internal fun drainStableIdGate(
    gate: StableIdDiscoveryGate,
    attempt: SessionAttemptIdentity,
    supported: Boolean,
    deliver: (StableIdMessageDecision) -> Unit,
) {
    try {
        var batch = gate.complete(attempt, supported)
        while (true) {
            batch.forEach(deliver)
            batch = gate.drain(attempt)
            if (batch.isEmpty()) return
        }
    } catch (failure: Throwable) {
        gate.retire(attempt)
        throw failure
    }
}

internal fun <T> resolveStableIdGateOnCapabilityFailure(
    gate: StableIdDiscoveryGate,
    attempt: SessionAttemptIdentity,
    deliver: (StableIdMessageDecision) -> Unit,
    discover: () -> T,
): T = try {
    discover()
} catch (cancelled: CancellationException) {
    gate.retire(attempt)
    throw cancelled
} catch (failure: Exception) {
    drainStableIdGate(gate, attempt, supported = false, deliver)
    throw failure
}

internal fun archiveHasEarlier(
    direction: ArchivePageDirection,
    complete: Boolean,
    messageCount: Int,
    firstIndex: Int,
): Boolean = when {
    firstIndex == 0 -> false
    firstIndex > 0 -> true
    complete && messageCount == 0 && direction != ArchivePageDirection.AFTER -> false
    else -> true
}

internal fun isSmackSessionUsable(
    expectedBareJid: String,
    boundBareJid: String?,
    connected: Boolean,
    authenticated: Boolean,
    disconnectedButResumable: Boolean,
): Boolean = connected &&
    authenticated &&
    !disconnectedButResumable &&
    boundBareJid == expectedBareJid

internal class ConnectionLossNotifier(
    private val isUsable: () -> Boolean,
    private val onLoss: (SessionFailureReason) -> Unit,
) {
    private val local = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    fun connected() {
        delivered.set(false)
        local.set(false)
    }

    fun attemptStarting() {
        local.set(false)
    }

    fun attemptFailed() {
        delivered.set(true)
    }

    fun localDisconnect() {
        local.set(true)
    }

    fun remoteClosed(reason: SessionFailureReason = SessionFailureReason.NETWORK) {
        if (local.get() || isUsable()) return
        if (delivered.compareAndSet(false, true)) {
            if (isUsable()) {
                delivered.set(false)
            } else {
                onLoss(reason)
            }
        }
    }
}

internal class NemaProtocolParsingException(cause: Exception) :
    IOException("XMPP stanza parsing failed", cause)

internal fun handleNemaParsingException(failure: Exception) {
    if (failure !is MalformedMamResultException) throw NemaProtocolParsingException(failure)
}

internal object NemaParsingExceptionCallback : ParsingExceptionCallback {
    override fun handleUnparsableStanza(stanzaData: UnparseableStanza) {
        handleNemaParsingException(stanzaData.parsingException)
    }
}

internal fun Occupant.toRoomOccupant(): RoomOccupant? {
    val nick = nick?.toString()?.trim().orEmpty()
    if (nick.isEmpty()) return null
    return RoomOccupant(
        nick = nick,
        role = role?.toString()?.lowercase(),
        affiliation = affiliation?.toString()?.lowercase(),
    )
}
