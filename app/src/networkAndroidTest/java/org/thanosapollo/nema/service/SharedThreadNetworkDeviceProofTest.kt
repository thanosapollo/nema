package org.thanosapollo.nema.service

import android.app.Application
import android.os.Process
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime/Room/presenter proof only: no Activity, Compose, user accounts or production database. */
@RunWith(AndroidJUnit4::class)
class SharedThreadNetworkDeviceProofTest {
    @Test fun emptySharedDestinationsSurviveFreshClientsAndCarryExactBodies() {
        check(InstrumentationRegistry.getInstrumentation() is SharedThreadNetworkProofRunner) {
            "Use the opt-in bare-Application runner, never the ordinary instrumentation runner"
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        check(application.javaClass == Application::class.java)
        val argument = checkNotNull(InstrumentationRegistry.getArguments().getString("nemaThreadProofDirectory"))
        val directory = File(argument)
        require(directory.isAbsolute && directory == directory.canonicalFile && directory.isDirectory)
        val root = File(application.filesDir, "shared-thread-network-proof").canonicalFile
        require(directory.parentFile == root) { "Fixture must be inside the app-owned proof directory" }
        fun privateFile(file: File): File {
            require(file == file.canonicalFile && file.parentFile == directory && file.isFile)
            val stat = Os.stat(file.path)
            require(stat.st_uid == Process.myUid() && stat.st_mode and 0x3f == 0) {
                "Fixture and CA must be app-owned and private"
            }
            return file
        }
        val fixture = privateFile(File(directory, "fixture.json"))
        val certificate = File(JSONObject(fixture.readText()).getString("ca_certificate"))
        privateFile(certificate)
        SharedThreadNetworkJourney(application).run(fixture)
    }
}
