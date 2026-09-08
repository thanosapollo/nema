package org.thanosapollo.nema

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Preserve the accepted Cut & wind geometry, transparent cuts, scale and resource wiring.
 * Do not refresh these pins or substitute the old chat bubble without explicit artwork approval.
 * Startup shares the adaptive launcher; the separate in-app mark is outside this contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LauncherArtworkTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `approved SVGs and every launcher layer remain byte exact`() {
        val repository = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        // Taken from 3e1ee34511f5c1aab42ecef1f550174e0766eab2, not the working tree.
        val approved = mapOf(
            "app/src/main/res/drawable/ic_launcher_foreground.xml" to "be6494e8922a97bda10e82f876f69c4ce05f075289b519f500bdbac431963a8e",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml" to "8012d78f8174cc28138365473521faa37d5bc536361b6c395d52c18a38f7f9cb",
            "app/src/main/res/drawable/ic_launcher_background.xml" to "1d7ed95ba302dbf23974042cb802c285a9f300703bfd32b8edda598143b8794b",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-anydpi/ic_launcher.xml" to "9a46d8c326e8391e9e324c4dabdee45cca659ce8436c779e5b5a5cc64f2aa943",
            "app/src/main/res/mipmap-anydpi/ic_launcher_round.xml" to "4987755e368904e68a08670677ac693126267aa4e8d227c8abd787f3a334ef32",
            "artwork/launcher/nema.svg" to "83ef2e34be39f5695f3c4bfa5b33f831d8f25499e90d5043368b967c2e2a9920",
            "artwork/launcher/nema-editable.svg" to "443b99981ae11912d0d3142855bc024e95f920e798bcb2f83286d1c6eccef489",
        )
        approved.forEach { (path, digest) ->
            assertTrue("Missing approved Cut & wind artwork: $path", Files.isRegularFile(repository.resolve(path)))
            assertEquals("Cut & wind changed: $path; see artwork/launcher/README.md", digest,
                sha256(Files.readAllBytes(repository.resolve(path))))
        }
        val variants = Files.walk(repository.resolve("app/src")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("ic_launcher") }
                .map { repository.relativize(it).toString() }.toList().toSet()
        }
        assertEquals("Unreviewed launcher resource override", approved.keys.filter { it.startsWith("app/") }.toSet(), variants)
    }

    @Test
    fun `launcher round themed and startup resolve approved artwork`() = assertArtworkResolution()

    @Test
    @Config(qualifiers = "night")
    fun `night startup and themed launcher resolve approved artwork`() = assertArtworkResolution()

    private fun assertArtworkResolution() {
        val info = application.packageManager.getActivityInfo(
            ComponentName(application, MainActivity::class.java), PackageManager.GET_META_DATA,
        )
        assertEquals(R.mipmap.ic_launcher, info.iconResource)
        assertEquals(R.mipmap.ic_launcher, application.applicationInfo.icon)
        // Inspect the compiled manifest; roundIcon has no public ApplicationInfo field.
        var roundIcon = 0
        application.assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "application") {
                    roundIcon = xml.getAttributeResourceValue(androidNamespace, "roundIcon", 0)
                    break
                }
            }
        }
        assertEquals(R.mipmap.ic_launcher_round, roundIcon)
        assertAdaptiveIcon(R.mipmap.ic_launcher)
        assertAdaptiveIcon(R.mipmap.ic_launcher_round)

        val theme = ContextThemeWrapper(application, info.themeResource).theme
        val splash = TypedValue()
        theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, splash, true)
        // Android 12+ uses the Activity/application icon when the theme has no explicit splash icon.
        val startupIcon = splash.resourceId.takeIf { it != 0 } ?: info.iconResource
        assertEquals("Startup must reuse the approved adaptive launcher", R.mipmap.ic_launcher, startupIcon)
        assertAdaptiveIcon(startupIcon)
    }

    private fun assertAdaptiveIcon(resource: Int) {
        val layers = mutableMapOf<String, Int>()
        application.resources.getXml(resource).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                if (xml.depth == 1) assertEquals("adaptive-icon", xml.name)
                else layers[xml.name] = xml.getAttributeResourceValue(androidNamespace, "drawable", 0)
            }
        }
        assertEquals(setOf("background", "foreground", "monochrome"), layers.keys)
        assertEquals(R.drawable.ic_launcher_background, layers["background"])
        assertEquals(R.drawable.ic_launcher_foreground, layers["foreground"])
        val monochrome = application.resources.getIdentifier("ic_launcher_monochrome", "drawable", application.packageName)
        assertTrue("Missing themed Cut & wind layer", monochrome != 0)
        assertEquals(monochrome, layers["monochrome"])
        listOf(layers.getValue("foreground"), monochrome).forEach { vector ->
            val paths = mutableListOf<String>()
            application.resources.getXml(vector).use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                        paths += xml.getAttributeValue(androidNamespace, "pathData")
                    }
                }
            }
            assertEquals("Expected the single approved even-odd spool path", 1, paths.size)
            assertEquals("Resolved launcher/startup geometry changed",
                "944cd8fa2acfdaeeb9c2ed061bcfa005a2198b4eb97a9d1efb531600b8181565", sha256(paths.single().toByteArray()))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
