package org.thanosapollo.nema.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt in only through the pinned disposable-server hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedThreadNetworkProofTest {
    @Test fun emptySharedDestinationsSurviveFreshClientsAndCarryExactBodies() {
        val path = System.getenv("NEMA_THREAD_PROOF_CLIENT_CONFIG")
        assumeTrue("No authorized disposable-server fixture supplied", path != null)
        SharedThreadNetworkJourney(ApplicationProvider.getApplicationContext())
            .run(File(checkNotNull(path)))
    }
}
