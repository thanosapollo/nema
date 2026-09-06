package org.thanosapollo.nema.xmpp.transport

/** A repeat-safe archive request failed temporarily, not a storage or validation failure. */
class TransientArchiveException(cause: Exception) : Exception(cause)
