package org.thanosapollo.nema.ui.chat

import android.graphics.BitmapFactory as AndroidBitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PeerAvatar(
    label: String,
    photoBytes: ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val bitmap = remember(photoBytes) {
        photoBytes?.let(::decodeAvatarBytes)?.asImageBitmap()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = avatarGlyph(label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

internal fun decodeAvatarBytes(
    bytes: ByteArray,
    maxEdge: Int = MAX_AVATAR_EDGE,
): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val bounds = AndroidBitmapFactory.Options().apply { inJustDecodeBounds = true }
        AndroidBitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        val options = AndroidBitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
        }
        AndroidBitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

internal fun avatarGlyph(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "?"
    val ch = trimmed.first { !it.isWhitespace() }
    return ch.uppercaseChar().toString()
}

private const val MAX_AVATAR_EDGE = 256
