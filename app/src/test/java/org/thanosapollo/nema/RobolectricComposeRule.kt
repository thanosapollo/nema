package org.thanosapollo.nema

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * Supplies Compose's host Activity only to the Robolectric tests that need it.
 * App-module unit tests load the app's binary manifest, not the test manifest;
 * adding ui-test-manifest to testImplementation therefore cannot register it.
 */
fun createRobolectricComposeRule(): ComposeContentTestRule {
    val compose = createComposeRule()
    return object : ComposeContentTestRule by compose {
        override fun apply(base: Statement, description: Description): Statement {
            val test = compose.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    val app = ApplicationProvider.getApplicationContext<Application>()
                    val packageManager = shadowOf(app.packageManager)
                    val activity = packageManager.addActivityIfNotPresent(
                        ComponentName(app, ComponentActivity::class.java),
                    )
                    activity.exported = true
                    activity.theme = android.R.style.Theme_Material_Light_NoActionBar
                    packageManager.addOrUpdateActivity(activity)
                    test.evaluate()
                }
            }
        }
    }
}
