package org.thanosapollo.nema.xmpp.httpupload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Local admission failures never authorize an upload or a draft mutation. */
enum class AttachmentRejection(val message: String) {
    TooLarge("Attachment is too large (maximum 25 MiB)"),
    Empty("Attachment is empty"),
    Unreadable("Unable to read attachment"),
}

class AttachmentPreparationException(
    val reason: AttachmentRejection,
    cause: Exception? = null,
) : IllegalArgumentException(reason.message, cause)

internal fun requireUploadSize(size: Long) {
    if (size > MAX_ATTACHMENT_BYTES) throw AttachmentPreparationException(AttachmentRejection.TooLarge)
    if (size <= 0) throw AttachmentPreparationException(AttachmentRejection.Empty)
}

/** Metadata is only an early rejection hint; the stream is always bounded independently. */
internal suspend fun ContentResolver.prepareOutgoingAttachment(uri: Uri): LocalUploadRequest = withContext(Dispatchers.IO) {
    val owner = currentCoroutineContext()
    owner.ensureActive()
    try {
        val declaredSize = try {
            query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            owner.ensureActive()
            null // Providers without size metadata still use the same bounded reader.
        }
        owner.ensureActive()
        if (declaredSize != null && declaredSize > MAX_ATTACHMENT_BYTES) {
            throw AttachmentPreparationException(AttachmentRejection.TooLarge)
        }
        val mime = getType(uri)
        val name = slotFilename(uri.lastPathSegment?.substringAfterLast('/') ?: "file", mime)
        owner.ensureActive()
        val bytes = openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                owner.ensureActive()
                // Read at most one rejection sentinel beyond the limit, even for unknown sizes.
                val remaining = MAX_ATTACHMENT_BYTES - out.size().toLong()
                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                owner.ensureActive()
                if (count < 0) break
                if (count.toLong() > remaining) throw AttachmentPreparationException(AttachmentRejection.TooLarge)
                out.write(buffer, 0, count)
            }
            requireUploadSize(out.size().toLong())
            owner.ensureActive()
            out.toByteArray()
        } ?: throw AttachmentPreparationException(AttachmentRejection.Unreadable)
        owner.ensureActive()
        LocalUploadRequest(name, mime, bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: AttachmentPreparationException) {
        owner.ensureActive()
        throw rejected
    } catch (failure: Exception) {
        // An IO failure after cancellation must not become an ordinary read rejection.
        owner.ensureActive()
        throw AttachmentPreparationException(AttachmentRejection.Unreadable, failure)
    }
}
