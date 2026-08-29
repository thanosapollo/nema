package org.thanosapollo.nema

import android.app.Application
import android.os.Bundle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityAutomaticUpdateTest {
    @Test
    fun `fresh launch starts automatic update check`() {
        assertTrue(shouldStartAutomaticUpdateCheck(null))
    }

    @Test
    fun `activity recreation does not start automatic update check`() {
        assertFalse(shouldStartAutomaticUpdateCheck(Bundle()))
    }

    @Test
    fun `activity starts sole automatic update check only on fresh launch`() {
        val activity = File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()
        val guardedCheck = Regex(
            """if \(shouldStartAutomaticUpdateCheck\(savedInstanceState\)\) \{\s*""" +
                """lifecycleScope\.launch \{ \(application as NemaApplication\)\.updates\.checkAutomatic\(\) \}\s*}""",
        )

        assertTrue(guardedCheck.containsMatchIn(activity))
        assertEquals(1, "updates\\.checkAutomatic\\(\\)".toRegex().findAll(activity).count())
    }
}
