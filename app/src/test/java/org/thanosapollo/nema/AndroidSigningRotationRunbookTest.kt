package org.thanosapollo.nema

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSigningRotationRunbookTest {
    @Test
    fun `signing rotation runbook records the reviewed release contract`() {
        val repository = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Could not locate repository root from ${Paths.get("").toAbsolutePath()}")
        val runbook = repository.resolve("docs/android-signing-rotation.md")

        assertTrue("Missing tracked runbook: $runbook", Files.isRegularFile(runbook))
        val text = String(Files.readAllBytes(runbook), Charsets.UTF_8)

        requireAll(
            text,
            "Build Tools 36.0.0",
            "apksigner 36.0.0",
            "--rotation-min-sdk-version 33",
            "installed-data=true",
            "--set-installed-data true",
            "shared-uid=false",
            "permission=false",
            "rollback=false",
            "auth=false",
            "apksigner rotate",
            "apksigner sign",
            "--lineage",
            "apksigner verify --verbose --print-certs --min-sdk-version 26",
            "API 26-27: original current, exact",
            "API 28-32: original current",
            "API 33+: new current, old -> new history",
            "PackageManager archive",
            "unchanged sentinel data",
            "physical API 26",
            "physical API 28",
            "physical API 33",
        )
        requireOccurrences(text, "--ks-pass env:OLD_STORE_PASS", 2)
        requireOccurrences(text, "--key-pass env:OLD_KEY_PASS", 2)
        requireOccurrences(text, "--ks-pass env:NEW_STORE_PASS", 2)
        requireOccurrences(text, "--key-pass env:NEW_KEY_PASS", 2)
        assertFalse("Runbook must not disable installed-data capability", text.contains("--set-installed-data false"))
        assertFalse("Runbook must not contradict the installed-data contract", text.contains("`installed-data=false`"))
        listOf("shared-uid", "permission", "rollback", "auth").forEach { capability ->
            assertFalse("Runbook must not enable $capability capability", text.contains("--set-$capability true"))
        }
        assertFalse("Runbook must not use a fictitious old signer password option", text.contains("--old-signer-pass"))
        assertFalse("Runbook must not use a fictitious new signer password option", text.contains("--new-signer-pass"))
        assertFalse("Runbook must not contain a literal password", Regex("(?i)(?:--\\S*pass(?:word)?|password)\\s*(?:=|\\s)\\s*(?!env:|stdin(?:\\s|$))[^\\s`]+")
            .containsMatchIn(text))
    }

    private fun requireAll(text: String, vararg requirements: String) {
        requirements.forEach { requirement ->
            assertTrue("Missing runbook contract: $requirement", text.contains(requirement))
        }
    }

    private fun requireOccurrences(text: String, requirement: String, expected: Int) {
        assertTrue(
            "Expected $expected occurrences of runbook contract: $requirement",
            text.split(requirement).size - 1 == expected,
        )
    }
}
