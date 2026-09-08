package org.thanosapollo.nema.thread

/** Validation for new local names; never apply this to received message bodies. */
fun threadNameError(value: String): String? = when {
    value.trim().isEmpty() -> "Enter a thread name."
    value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> "Use a single line without control characters."
    value.trim().codePointCount(0, value.trim().length) > 80 -> "Use 80 characters or fewer."
    else -> null
}
