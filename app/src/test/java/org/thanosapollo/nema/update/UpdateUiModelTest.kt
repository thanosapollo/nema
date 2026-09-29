package org.thanosapollo.nema.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiModelTest {
    @Test
    fun mapsEveryRepositoryStateToTheAllowedActions() {
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val artifact = BoundUpdateArtifact(accepted, java.io.File("candidate.apk"), 1, "a".repeat(64))
        val facts = PackageFacts(NEMA_PACKAGE_NAME, 2, setOf("signer"), setOf("signer"))
        val verified = VerifiedUpdate(artifact, facts, facts.copy(versionCode = 3))
        val states = listOf(
            UpdateState.Idle to Triple(null, true, false),
            UpdateState.Checking(null) to Triple("Checking…", false, false),
            UpdateState.Current(accepted) to Triple("Nema is up to date", true, false),
            UpdateState.Available(accepted) to Triple("Nema 0.2 is available", true, true),
            UpdateState.Downloading(accepted) to Triple("Downloading/verifying…", false, false),
            UpdateState.Downloaded(accepted, artifact) to Triple("Downloading/verifying…", false, false),
            UpdateState.Verified(accepted, verified) to Triple("Nema 0.2 is ready to install", true, false),
            UpdateState.Installing(accepted, InstallHandoffLease(UpdateState.Verified(accepted, verified))) to
                Triple("Opening system installer…", false, false),
            UpdateState.Failed("internal detail", accepted) to
                Triple("Update failed. Try again.", true, true),
        )

        states.forEach { (state, expected) ->
            val model = updateUiModel("0.1.1", 2, state)
            assertEquals("Current version: Nema 0.1.1 (2)", model.currentVersion)
            assertEquals(expected.first, model.status)
            assertEquals(expected.second, model.canCheck)
            assertEquals(expected.third, model.canDownload)
            assertEquals(state is UpdateState.Verified, model.canInstall)
        }
    }

    @Test
    fun downloadedNeverExposesInstallAndIdleHasNoStatus() {
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val model = updateUiModel(
            "0.1.1",
            2,
            UpdateState.Downloaded(accepted, BoundUpdateArtifact(accepted, java.io.File("x"), 1, "a".repeat(64))),
        )

        assertFalse(model.canInstall)
        assertNull(updateUiModel("0.1.1", 2, UpdateState.Idle).status)
        assertTrue(updateUiModel("0.1.1", 2, UpdateState.Idle).canCheck)
    }

    private fun manifest() = UpdateManifest(
        1, NEMA_PACKAGE_NAME, 3, "0.2", "https://git.thanosapollo.org/nema/releases/nema.apk",
        1, "a".repeat(64), "b".repeat(64), "c".repeat(40),
    )
}
