package org.thanosapollo.nema.ui.chat

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AvatarDecodeTest {
    @Test
    fun emptyYieldsNullAndBoundedDecodeKeepsEdge() {
        assertNull(decodeAvatarBytes(ByteArray(0)))
        val huge = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        huge.eraseColor(Color.RED)
        val stream = ByteArrayOutputStream()
        huge.compress(Bitmap.CompressFormat.PNG, 100, stream)
        huge.recycle()
        val decoded = requireNotNull(decodeAvatarBytes(stream.toByteArray(), maxEdge = 256))
        assertTrue(decoded.width <= 256)
        assertTrue(decoded.height <= 256)
        decoded.recycle()
    }

    @Test
    fun largeDimensionPayloadDownsamplesWithinEdge() {
        val huge = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        huge.eraseColor(Color.GREEN)
        val stream = ByteArrayOutputStream()
        huge.compress(Bitmap.CompressFormat.PNG, 100, stream)
        huge.recycle()
        val decoded = requireNotNull(decodeAvatarBytes(stream.toByteArray(), maxEdge = 128))
        assertTrue(decoded.width <= 128)
        assertTrue(decoded.height <= 128)
        decoded.recycle()
    }

    @Test
    fun normalAvatarDecodes() {
        val small = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        small.eraseColor(Color.BLUE)
        val stream = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, stream)
        small.recycle()
        val decoded = decodeAvatarBytes(stream.toByteArray())
        assertNotNull(decoded)
        decoded!!.recycle()
    }
}
