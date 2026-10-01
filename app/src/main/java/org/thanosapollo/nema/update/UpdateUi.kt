package org.thanosapollo.nema.update

data class UpdateUiModel(
    val currentVersion: String,
    val status: String?,
    val canCheck: Boolean,
    val canDownload: Boolean,
    val canInstall: Boolean,
    val canCancel: Boolean = false,
)

fun updateUiModel(versionName: String, versionCode: Long, state: UpdateState): UpdateUiModel {
    val available = state.accepted?.value as? AcceptedUpdate.Available
    val status = when (state) {
        UpdateState.Idle -> null
        is UpdateState.Checking -> "Checking…"
        is UpdateState.Current -> "Nema is up to date"
        is UpdateState.Available -> "Nema ${available?.manifest?.versionName} is available"
        is UpdateState.Downloading, is UpdateState.Downloaded -> "Downloading/verifying…"
        is UpdateState.Verified -> "Nema ${available?.manifest?.versionName} is ready to install"
        is UpdateState.Installing -> "Opening system installer…"
        is UpdateState.Failed -> "Update failed. Try again."
    }
    val preparing = state is UpdateState.Downloading || state is UpdateState.Downloaded
    val checkBlocked = state is UpdateState.Checking || state is UpdateState.Installing || preparing
    return UpdateUiModel(
        currentVersion = "Current version: Nema $versionName ($versionCode)",
        status = status,
        canCheck = !checkBlocked,
        canDownload = state is UpdateState.Available ||
            state is UpdateState.Failed && available != null,
        canInstall = state is UpdateState.Verified,
        canCancel = preparing,
    )
}
