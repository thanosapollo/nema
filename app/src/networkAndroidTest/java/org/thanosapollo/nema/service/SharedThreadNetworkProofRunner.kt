package org.thanosapollo.nema.service

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Selected only by -PnemaSharedThreadNetworkProof=true. Never instantiate NemaApplication. */
class SharedThreadNetworkProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle) {
        require(arguments.getString("class") == SharedThreadNetworkDeviceProofTest::class.java.name) {
            "This isolated runner accepts only the shared-thread network journey"
        }
        require(!arguments.getString("nemaThreadProofDirectory").isNullOrBlank()) {
            "An explicit private disposable fixture directory is required"
        }
        super.onCreate(arguments)
    }
}
