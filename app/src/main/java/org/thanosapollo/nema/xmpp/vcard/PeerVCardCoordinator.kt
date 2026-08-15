package org.thanosapollo.nema.xmpp.vcard

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException

fun interface VCardLoader {
    suspend fun load(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): RemoteVCardPayload
}

/**
 * Visible-peer vCard fetch with success/failure TTL and single in-flight per peer.
 * No background crawler.
 */
class PeerVCardCoordinator(
    private val store: PeerIdentityStore,
    private val loader: VCardLoader,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val successTtlMs: Long = VCARD_SUCCESS_TTL_MS,
    private val failureTtlMs: Long = VCARD_FAILURE_TTL_MS,
    private val maxPhotoBytes: Int = MAX_VCARD_PHOTO_BYTES,
) {
    private val gates = ConcurrentHashMap<String, Mutex>()

    suspend fun ensure(
        accountId: AccountId,
        generation: ConnectionGeneration,
        peerJids: Collection<String>,
    ) {
        val now = clockMs()
        for (raw in peerJids) {
            val peerJid = raw.trim()
            if (peerJid.isEmpty()) continue
            val gate = gates.getOrPut("${accountId.value}\u0000$peerJid") { Mutex() }
            if (!gate.tryLock()) continue
            try {
                val existing = store.peer(accountId.value, peerJid)
                if (!peerVCardNeedsFetch(
                        existing = existing,
                        nowMs = now,
                        successTtlMs = successTtlMs,
                        failureTtlMs = failureTtlMs,
                    )
                ) {
                    continue
                }
                try {
                    val parsed = parseVCardIdentity(
                        loader.load(accountId, generation, peerJid),
                        maxPhotoBytes = maxPhotoBytes,
                    )
                    val photoBytes = parsed.photoBytes ?: existing?.photoBytes
                    val photoMime = if (parsed.photoBytes != null) parsed.photoMime else existing?.photoMime
                    val photoSha1 = if (parsed.photoBytes != null) parsed.photoSha1 else existing?.photoSha1
                    store.saveSuccess(
                        PeerEntity(
                            accountId = accountId.value,
                            jid = peerJid,
                            displayName = parsed.displayName,
                            photoMime = photoMime,
                            photoBytes = photoBytes,
                            photoSha1 = photoSha1,
                            vcardFetchedAtMs = now,
                            vcardFailureAtMs = null,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SendNotAttemptedException) {
                    // Stale generation / disconnect: no failure stamp (retry when connected).
                } catch (_: Exception) {
                    store.saveFailure(
                        accountId = accountId.value,
                        peerJid = peerJid,
                        failureAtMs = now,
                    )
                }
            } finally {
                gate.unlock()
            }
        }
    }
}

fun peerVCardNeedsFetch(
    existing: PeerEntity?,
    nowMs: Long,
    successTtlMs: Long = VCARD_SUCCESS_TTL_MS,
    failureTtlMs: Long = VCARD_FAILURE_TTL_MS,
): Boolean {
    if (existing == null) return true
    if (existing.room) return false
    val fetched = existing.vcardFetchedAtMs
    if (fetched != null && nowMs - fetched < successTtlMs) return false
    val failed = existing.vcardFailureAtMs
    if (failed != null && nowMs - failed < failureTtlMs) return false
    return true
}
