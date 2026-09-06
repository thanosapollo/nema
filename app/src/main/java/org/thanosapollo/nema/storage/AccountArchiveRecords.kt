package org.thanosapollo.nema.storage

/** Transaction-local raw ordering preflight. Aliases own identity; this ledger never does. */
internal class AccountArchiveRecords private constructor(
    private val page: ArchivePage,
    private val current: ArchiveCursorEntity?,
    val anchors: Map<Int, Long>,
    private val recorded: Set<String>,
    private val legacyOwners: Map<Int, String>,
) {
    val startOrdinal: Long?
        get() = anchors.entries.firstOrNull()?.let { Math.subtractExact(it.value, it.key.toLong()) }

    suspend fun validate(dao: MessageDao, start: Long, tentativeOwners: Set<String>): String? {
        if (anchors.any { (index, ordinal) -> ordinal != Math.addExact(start, index.toLong()) }) {
            return "Archive overlap has incompatible ordering"
        }
        if (page.messages.isEmpty()) return null
        val end = Math.addExact(start, page.messages.lastIndex.toLong())
        val oldest = current?.oldestOrdinal
        val newest = current?.newestOrdinal
        if (page.direction == ArchiveDirection.BEFORE && oldest != null && start >= oldest ||
            page.direction == ArchiveDirection.AFTER && newest != null && end <= newest
        ) return "Archive page repeated without progress"
        if (page.direction == ArchiveDirection.AFTER && newest != null && start > Math.addExact(newest, 1L) ||
            page.direction == ArchiveDirection.BEFORE && oldest != null && end < Math.subtractExact(oldest, 1L)
        ) return "Archive page leaves a gap at the requested boundary"
        for ((index, owner) in legacyOwners) {
            val ordinal = Math.addExact(start, index.toLong())
            if (owner !in tentativeOwners && (oldest == null || newest == null || ordinal !in oldest..newest)) {
                return "Legacy archive alias has no proven occurrence outside coverage"
            }
        }
        val key = page.key
        for (record in dao.archiveRecords(key.accountId, key.archiveAuthority, key.scope, start, end)) {
            val index = Math.subtractExact(record.archiveOrdinal, start).toInt()
            if (page.messages[index].resultId != record.resultId) return "Archive ordinal belongs to another result"
        }
        return null
    }

    suspend fun persist(dao: MessageDao, start: Long) {
        val key = page.key
        page.messages.forEachIndexed { index, record ->
            if (record.resultId !in recorded) dao.insertArchiveRecord(ArchiveRecordPositionEntity(
                key.accountId, key.archiveAuthority, key.scope, record.resultId,
                Math.addExact(start, index.toLong()),
            ))
        }
    }

    companion object {
        suspend fun load(dao: MessageDao, page: ArchivePage, current: ArchiveCursorEntity?): AccountArchiveRecords {
            val key = page.key
            val anchors = mutableMapOf<Int, Long>()
            val recorded = mutableSetOf<String>()
            val legacyOwners = mutableMapOf<Int, String>()
            page.messages.forEachIndexed { index, record ->
                val exact = dao.archiveRecord(key.accountId, key.archiveAuthority, key.scope, record.resultId)
                if (exact != null) {
                    anchors[index] = exact.archiveOrdinal
                    recorded += record.resultId
                } else {
                    val alias = dao.trustedAlias(key.accountId, IdentityAliasKind.MAM_RESULT, key.aliasAuthority(), record.resultId)
                    val owner = alias?.messageId
                    if (owner != null) {
                        legacyOwners[index] = owner
                        val position = dao.archivePosition(key.accountId, key.archiveAuthority, key.scope, owner)
                        val oldest = current?.oldestOrdinal
                        val newest = current?.newestOrdinal
                        // The sole lazy legacy-anchor predicate. Tentative prefixes and multi-alias
                        // owners cannot certify which transmission occupied a canonical ordinal.
                        if (position != null && oldest != null && newest != null &&
                            position.archiveOrdinal in oldest..newest &&
                            dao.trustedArchiveResultIds(key.accountId, key.aliasAuthority(), owner) == listOf(record.resultId)
                        ) anchors[index] = position.archiveOrdinal
                    }
                }
            }
            return AccountArchiveRecords(page, current, anchors, recorded, legacyOwners)
        }
    }
}
