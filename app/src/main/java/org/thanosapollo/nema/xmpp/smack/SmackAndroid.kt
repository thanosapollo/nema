package org.thanosapollo.nema.xmpp.smack

import android.content.Context
import org.jivesoftware.smack.SmackConfiguration
import org.jivesoftware.smack.android.AndroidSmackInitializer

internal object SmackAndroid {
    fun initialize(context: Context) {
        SmackConfiguration.DEBUG = false
        AndroidSmackInitializer.initialize(context.applicationContext)
    }
}
