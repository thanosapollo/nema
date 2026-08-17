package org.thanosapollo.nema.xmpp.smack

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
import org.thanosapollo.nema.xmpp.chatstates.chatActivityNamed
import org.thanosapollo.nema.xmpp.chatstates.installNemaChatStateProviders
import org.thanosapollo.nema.xmpp.reactions.installNemaReactionProviders
import org.thanosapollo.nema.xmpp.reactions.parseReactions
import org.thanosapollo.nema.xmpp.transport.IncomingChatState
import org.thanosapollo.nema.xmpp.transport.IncomingReactionEnvelope
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
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities
import org.thanosapollo.nema.xmpp.transport.StanzaIdEnvelope
import org.thanosapollo.nema.xmpp.vcard.RemoteVCardPayload

class SmackSessionConnectionFactory : SessionConnectionFactory {
    override fun create(
        configuration: AccountConfiguration,
        identity: SessionIdentity,
        event: (SessionEvent) -> Unit,
    ): SessionConnection {
        requireNemaMamResultProvider()
        installNemaReplyProviders()
        installNemaChatMarkerProviders()
        installNemaChatStateProviders()
        installNemaReactionProviders()
        require(identity.accountId == configuration.id) { "Session account does not match configuration" }
        val connection = XMPPTCPConnection(configurationFor(configuration)).apply {
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
            event = event,
        )
    }

    companion object {
        fun configurationFor(
            configuration: AccountConfiguration,
        ): XMPPTCPConnectionConfiguration {
            val builder = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom(configuration.serviceDomain.value))
                .setUsernameAndPassword(configuration.authenticationId.value, null)
                .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
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
    }
}

internal class SmackSessionConnection(
    private val connection: XMPPTCPConnection,
    private val authenticationId: String,
    private val expectedBareJid: String,
    private val event: (SessionEvent) -> Unit,
) : SessionConnection {
    private val entryGate = Any()
    private val revoked = AtomicBoolean(false)
    private val disconnectStarted = AtomicBoolean(false)
    private val stableIdGate = StableIdDiscoveryGate()
    private val watchedRooms = mutableSetOf<String>()
    private val roomNicks = ConcurrentHashMap<String, String>()
    private val roomDiscoNames = ConcurrentHashMap<String, String>()
    private val connectionListener = AttemptConnectionListener().also(connection::addConnectionListener)
    private val messageListener = StanzaListener { stanza ->
        if (revoked.get()) return@StanzaListener
        val wrapper = stanza as? Message ?: return@StanzaListener
        val attempt = connectionListener.currentAttempt() ?: return@StanzaListener
        val failure = wrapper.classifyOutgoingFailure(expectedBareJid)
        if (failure.consumed) {
            failure.failure?.let { event(SessionEvent.OutgoingFailure(attempt, it)) }
            return@StanzaListener
        }
        val message = wrapper.toTrustedCarbonMessage(expectedBareJid) ?: return@StanzaListener
        message.message.toIncomingSignal(attempt, expectedBareJid)?.let {
            event(SessionEvent.Signal(attempt, it))
            return@StanzaListener
        }
        val room = message.message.from?.asBareJid()?.toString()
        message.message.toIncomingChatState(attempt, expectedBareJid, room?.let(roomNicks::get))?.let {
            event(SessionEvent.ChatState(attempt, it))
        }
        message.message.toIncomingReaction(attempt, expectedBareJid)?.let {
            event(SessionEvent.Reaction(attempt, it))
            if (message.message.body.isNullOrEmpty()) return@StanzaListener
        }
        stableIdGate.accept(attempt, message).forEach(::deliver)
    }
    init {
        connection.addStanzaListener(messageListener, StanzaTypeFilter.MESSAGE)
    }

    override val isUsable: Boolean
        get() = !revoked.get() && isSmackSessionUsable(
            expectedBareJid = expectedBareJid,
            boundBareJid = connection.user?.asBareJid()?.toString(),
            connected = connection.isConnected,
            authenticated = connection.isAuthenticated,
            disconnectedButResumable = connection.isDisconnectedButSmResumptionPossible,
        )

    override fun revoke() {
        synchronized(entryGate) {
            if (revoked.compareAndSet(false, true)) {
                stableIdGate.retireAll()
                roomNicks.clear()
                connectionListener.localDisconnect()
            }
        }
    }

    override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
        establish(attempt, credential)
    }

    override suspend fun reconnect(attempt: SessionAttemptIdentity) {
        establish(attempt, null)
    }

    override fun updateAttempt(attempt: SessionAttemptIdentity) {
        synchronized(entryGate) {
            if (!revoked.get()) {
                connectionListener.updateAttempt(attempt)
            }
        }
    }

    override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = runInterruptible(Dispatchers.IO) {
        val stanza = message.toSmackMessage()
        synchronized(entryGate) {
            val attempt = connectionListener.currentAttempt()
            if (revoked.get() ||
                attempt == null ||
                attempt.accountId != message.accountId ||
                attempt.generation != message.generation ||
                !isUsable
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

    override suspend fun discoverCapabilities(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): SessionCapabilities = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val attempt = requireNotNull(connectionListener.currentAttempt())
        resolveStableIdGateOnCapabilityFailure(stableIdGate, attempt, ::deliver) {
            val mam = MamManager.getInstanceFor(connection).isSupported
            val carbonManager = CarbonManager.getInstanceFor(connection)
            val carbons = enableLiveCarbons(carbonManager)
            val stableIds = ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(
                JidCreate.entityBareFrom(expectedBareJid),
                StableUniqueStanzaIdManager.NAMESPACE,
            )
            requireExactAttempt(accountId, generation)
            drainStableIdGate(stableIdGate, attempt, stableIds, ::deliver)
            SessionCapabilities(
                mamV2 = mam,
                carbons = carbons,
                carbonsEnabled = carbons && carbonManager.carbonsEnabled,
                stableIds = stableIds,
            )
        }
    }

    override suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope =
        runInterruptible(Dispatchers.IO) {
            val archiveJid = if (request.scope == ACCOUNT_ARCHIVE_SCOPE) {
                require(request.archiveAuthority == expectedBareJid) { "Unexpected archive authority" }
                expectedBareJid
            } else {
                require(request.scope == request.archiveAuthority) { "Room archive scope must match authority" }
                request.archiveAuthority
            }
            requireExactAttempt(request.accountId, request.generation)
            val attempt = requireNotNull(connectionListener.currentAttempt())
            val trustStableIds = request.scope == ACCOUNT_ARCHIVE_SCOPE && stableIdGate.support(attempt) == true
            val builder = MamManager.MamQueryArgs.builder().setResultPageSizeTo(request.pageSize)
            when (request.direction) {
                ArchivePageDirection.BOOTSTRAP -> builder.queryLastPage()
                ArchivePageDirection.BEFORE -> builder.beforeUid(requireNotNull(request.boundaryId))
                ArchivePageDirection.AFTER -> builder.afterUid(requireNotNull(request.boundaryId))
            }
            val queryPage = MamManager.getInstanceFor(
                connection,
                JidCreate.entityBareFrom(archiveJid),
            ).queryArchive(builder.build()).page
            requireExactAttempt(request.accountId, request.generation)
            if (connectionListener.currentAttempt() != attempt) throw SendNotAttemptedException()
            val fin = queryPage.mamFinIq
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
                ownRoomNick = roomNicks[archiveJid],
            )
            requireMamPageBoundaries(rsm.first, rsm.last, messages)
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

    override suspend fun uploadHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: LocalUploadRequest,
    ): UploadedFile? = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val manager = HttpFileUploadManager.getInstanceFor(connection)
        if (!manager.isUploadServiceDiscovered && !manager.discoverUploadService()) {
            return@runInterruptible null
        }
        requireExactAttempt(accountId, generation)
        val url = manager.uploadFile(request.bytes.inputStream(), request.name, request.size)
        requireExactAttempt(accountId, generation)
        UploadedFile(
            url = url.toString(),
            name = request.name,
            mime = request.mime,
            size = request.size,
        )
    }

    override suspend fun joinMuc(
        accountId: AccountId,
        generation: ConnectionGeneration,
        roomJid: String,
        nick: String?,
        password: String?,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        requireExactAttempt(accountId, generation)
        val room = JidCreate.entityBareFrom(roomJid)
        val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(room)
        val roomNick = preferredRoomNick(nick, expectedBareJid)
        val nickPart = Resourcepart.from(roomNick)
        listenToRoom(muc, roomJid)
        if (muc.isJoined) {
            rememberRoomDiscoName(room, roomJid)
            emitRoomView(muc, roomJid)
            return@runInterruptible true
        }
        val enter = muc.getEnterConfigurationBuilder(nickPart)
            .requestNoHistory()
            .let { builder ->
                val secret = password?.trim().orEmpty()
                if (secret.isEmpty()) builder else builder.withPassword(secret)
            }
            .build()
        try {
            muc.join(enter)
            rememberRoomDiscoName(room, roomJid)
            emitRoomView(muc, roomJid)
            true
        } catch (_: MultiUserChatException.MucAlreadyJoinedException) {
            rememberRoomDiscoName(room, roomJid)
            emitRoomView(muc, roomJid)
            true
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

    private fun listenToRoom(muc: MultiUserChat, roomJid: String) {
        if (!watchedRooms.add(roomJid)) return
        muc.addParticipantListener { emitRoomView(muc, roomJid) }
        muc.addSubjectUpdatedListener { _, _ -> emitRoomView(muc, roomJid) }
    }

    private fun emitRoomView(muc: MultiUserChat, roomJid: String) {
        val attempt = connectionListener.currentAttempt() ?: return
        muc.nickname?.toString()?.let { roomNicks[roomJid] = it }
        val occupants = muc.occupants.mapNotNull { occupantJid ->
            val occupant = muc.getOccupant(occupantJid) ?: return@mapNotNull null
            occupant.toRoomOccupant()
        }
        event(
            SessionEvent.RoomUpdated(
                attempt,
                RoomView(
                    roomJid = roomJid,
                    subject = muc.subject?.takeIf(String::isNotEmpty),
                    discoName = roomDiscoNames[roomJid],
                    occupants = occupants,
                    ownNick = muc.nickname?.toString(),
                ),
            ),
        )
    }

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
        val room = decision.message.from?.asBareJid()?.toString()
        decision.message.toIncomingEnvelope(
            decision.attempt,
            expectedBareJid,
            decision.trustStableIds,
            room?.let(roomNicks::get),
            decision.sentAtEpochMs,
            decision.sentTimeSource,
            decision.receivedAtEpochMs,
        )?.let { event(SessionEvent.Incoming(decision.attempt, it)) }
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
                connection.connect()
            }
            if (!connection.isSecureConnection) {
                connectionListener.localDisconnect()
                runCatching { connection.disconnect() }
                throw SessionFailure(SessionFailureReason.TLS_CERTIFICATE)
            }
            synchronized(connection) {
                ensureNotRevoked()
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
            enableLiveCarbons()
            if (isUsable) {
                connectionListener.connected()
            } else {
                connectionListener.attemptFailed()
            }
        } catch (failure: CancellationException) {
            connectionListener.attemptFailed()
            throw failure
        } catch (failure: SessionFailure) {
            connectionListener.attemptFailed()
            throw failure
        } catch (error: Exception) {
            connectionListener.attemptFailed()
            throw SessionFailure(classifySmackFailure(error), error)
        }
    }

    private fun enableLiveCarbons() {
        enableLiveCarbons(CarbonManager.getInstanceFor(connection))
    }

    private fun enableLiveCarbons(carbonManager: CarbonManager): Boolean {
        val supported = runCatching { carbonManager.isSupportedByServer }.getOrDefault(false)
        if (supported && !carbonManager.carbonsEnabled) {
            runCatching { carbonManager.enableCarbons() }
        }
        return supported && carbonManager.carbonsEnabled
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
            lossNotifier.remoteClosed(SessionFailureReason.NETWORK)
        }

        override fun connectionClosedOnError(error: Exception) {
            lossNotifier.remoteClosed(classifySmackFailure(error))
        }

        fun attemptStarting(attempt: SessionAttemptIdentity) {
            stableIdGate.begin(attempt)
            this.attempt = attempt
            lossNotifier.attemptStarting()
        }

        fun updateAttempt(attempt: SessionAttemptIdentity) {
            stableIdGate.begin(attempt)
            this.attempt = attempt
        }

        fun currentAttempt(): SessionAttemptIdentity? = attempt

        fun attemptFailed() = lossNotifier.attemptFailed()

        fun connected() = lossNotifier.connected()

        fun localDisconnect() = lossNotifier.localDisconnect()
    }

}

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

internal fun OutgoingMessageSignal.toSmackMessage(): Message {
    val builder = StanzaBuilder.buildMessage()
        .to(JidCreate.entityBareFrom(recipient))
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
    }
    return builder.build()
}

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
        if (sender == expectedBareJid) return null
        IncomingChatState(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = sender,
            actor = sender,
            groupChat = false,
            activity = activity,
        )
    }
}

internal fun Message.toIncomingReaction(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
): IncomingReactionEnvelope? {
    if (type != Message.Type.chat) return null
    val parsed = parseReactions() ?: return null
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

internal fun Message.toIncomingEnvelope(
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    trustedStableIdAuthority: Boolean = false,
    ownRoomNick: String? = null,
    suppliedSentAtEpochMs: Long? = null,
    suppliedSentTimeSource: MessageTimeSource? = null,
    receivedAtEpochMs: Long = System.currentTimeMillis(),
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
    val receiptRequested = type == Message.Type.chat &&
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
            ?.idInitialMessage
            ?.takeIf(String::isNotEmpty)
    } else {
        null
    }
    val share = oobShare()
    val reply = replyReference()
    val parsed = if (reply == null) null else parseReplyBody()
    val messageBody = if (parsed == null) {
        body?.takeIf(String::isNotEmpty) ?: share?.url
    } else {
        parsed.body.takeIf(String::isNotEmpty) ?: share?.url
    } ?: return null
    val replyEnvelope = reply?.copy(fallbackBody = parsed?.fallbackBody)
    return if (groupChat) {
        val room = fromJid.asBareJid().takeIf { it.isEntityBareJid }?.toString() ?: return null
        val occupant = fromJid.takeIf { it.isEntityFullJid }?.toString() ?: room
        val occupantNick = fromJid.resourceOrNull?.toString()
        IncomingMessageEnvelope(
            accountId = attempt.accountId,
            generation = attempt.generation,
            peer = room,
            sender = occupant,
            outbound = occupantNick != null && occupantNick == ownRoomNick,
            originId = getExtension(OriginIdElement::class.java)?.id,
            body = messageBody,
            thread = toThreadRef(),
            stanzaIds = getExtensions(StanzaIdElement::class.java)
                .filter { it.by == room }
                .map { StanzaIdEnvelope(it.id, it.by) },
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
            originId = getExtension(OriginIdElement::class.java)?.id,
            messageId = stanzaId,
            body = messageBody,
            thread = toThreadRef(),
            stanzaIds = if (trustedStableIdAuthority) {
                getExtensions(StanzaIdElement::class.java)
                    .filter { it.by == expectedBareJid }
                    .map { StanzaIdEnvelope(it.id, it.by) }
            } else {
                emptyList()
            },
            kind = MessageKind.CHAT,
            attachmentUrl = share?.url,
            attachmentName = share?.description,
            reply = replyEnvelope,
            sentAtEpochMs = sentAtEpochMs,
            sentTimeSource = sentTimeSource,
            receiptRequested = receiptRequested,
            markable = markable,
            replaceId = replaceId,
        )
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

internal fun Message.classifyOutgoingFailure(expectedBareJid: String): OutgoingFailureMapping {
    val received = getExtensionElement(
        CarbonExtension.Direction.received.name,
        CarbonExtension.NAMESPACE,
    ) as? CarbonExtension
    val sent = getExtensionElement(
        CarbonExtension.Direction.sent.name,
        CarbonExtension.NAMESPACE,
    ) as? CarbonExtension
    if (received == null && sent == null) {
        return if (type == Message.Type.error) {
            OutgoingFailureMapping(true, directFailure(expectedBareJid))
        } else {
            OutgoingFailureMapping(false, null)
        }
    }
    if (received != null && sent != null) return OutgoingFailureMapping(true, null)
    val carbon = received ?: sent ?: return OutgoingFailureMapping(false, null)
    val forwarded = carbon.forwarded.forwardedStanza as? Message
        ?: return OutgoingFailureMapping(true, null)
    if (forwarded.type != Message.Type.error) {
        return OutgoingFailureMapping(type == Message.Type.error, null)
    }
    if (carbon.direction != CarbonExtension.Direction.received ||
        !hasExactCarbonAuthority(expectedBareJid)
    ) {
        return OutgoingFailureMapping(true, null)
    }
    return OutgoingFailureMapping(true, forwarded.directFailure(expectedBareJid))
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
)

internal fun Message.toTrustedCarbonMessage(
    expectedBareJid: String,
    receivedAtEpochMs: Long = System.currentTimeMillis(),
): TrustedIncomingStanza? {
    val received = getExtensionElement(
        CarbonExtension.Direction.received.name,
        CarbonExtension.NAMESPACE,
    ) as? CarbonExtension
    val sent = getExtensionElement(
        CarbonExtension.Direction.sent.name,
        CarbonExtension.NAMESPACE,
    ) as? CarbonExtension
    if (received != null && sent != null) return null
    val carbon = received ?: sent ?: return TrustedIncomingStanza(this, receivedAtEpochMs = receivedAtEpochMs)
    if (!hasExactCarbonAuthority(expectedBareJid)) return null
    val forwarded = carbon.forwarded.forwardedStanza as? Message ?: return null
    val sender = forwarded.from?.asBareJid()?.toString() ?: return null
    val recipient = forwarded.to?.asBareJid()?.toString() ?: return null
    val trusted = when (carbon.direction) {
        CarbonExtension.Direction.sent -> forwarded.takeIf {
            sender == expectedBareJid && recipient != expectedBareJid
        }
        CarbonExtension.Direction.received -> forwarded.takeIf {
            sender != expectedBareJid && recipient == expectedBareJid
        }
    } ?: return null
    val delay = carbon.forwarded.delayInformation
    return TrustedIncomingStanza(
        message = trusted,
        sentAtEpochMs = delay?.stamp?.time,
        sentTimeSource = delay?.let { MessageTimeSource.CARBON },
        receivedAtEpochMs = receivedAtEpochMs,
    )
}

private fun Message.hasExactCarbonAuthority(expectedBareJid: String): Boolean =
    from?.let { it.isEntityBareJid && it.toString() == expectedBareJid } == true

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
): List<ArchiveMessageEnvelope> {
    require(carriers.size == results.size) { "MAM result metadata does not match carriers" }
    require(carriers.haveArchiveAuthority(expectedArchiveAuthority)) {
        "MAM result source does not match archive authority"
    }
    return results.map { result ->
        val owned = result as? NemaMamResultExtension
            ?: error("MAM result bypassed Nema normalization")
        val signal = owned.actualMessage
            ?.takeIf { expectedArchiveAuthority == mappingBareJid }
            ?.toIncomingSignal(attempt, mappingBareJid)
        val mappedMessage = if (signal == null) {
            owned.actualMessage?.toIncomingEnvelope(
                attempt,
                mappingBareJid,
                trustStableIds,
                ownRoomNick,
                owned.forwarded.delayInformation?.stamp?.time,
                owned.forwarded.delayInformation?.let { MessageTimeSource.MAM },
                receivedAtEpochMs,
            )
        } else {
            null
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
            State.Unknown -> {
                pending.addLast(message)
                emptyList()
            }
            is State.Draining -> {
                pending.addLast(message)
                emptyList()
            }
            is State.Open -> listOf(
                message.toDecision(attempt, current.supported),
            )
        }
    }

    @Synchronized
    fun complete(attempt: SessionAttemptIdentity, supported: Boolean): List<StableIdMessageDecision> {
        if (this.attempt != attempt) return emptyList()
        return when (val current = state) {
            State.Unknown -> {
                state = State.Draining(supported)
                takePending(attempt, supported)
            }
            is State.Draining -> {
                require(current.supported == supported) { "Stable ID discovery decision changed" }
                emptyList()
            }
            is State.Open -> {
                require(current.supported == supported) { "Stable ID discovery decision changed" }
                emptyList()
            }
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
