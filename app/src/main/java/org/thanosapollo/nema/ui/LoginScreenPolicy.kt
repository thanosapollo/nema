package org.thanosapollo.nema.ui

import org.thanosapollo.nema.session.ConnectionState

/** Session chrome (status, Stop, Sign out) only when account exists or session left idle Stopped. */
fun showLoginSessionChrome(
    hasSavedAccount: Boolean,
    connectionState: ConnectionState,
): Boolean = hasSavedAccount || connectionState !is ConnectionState.Stopped
