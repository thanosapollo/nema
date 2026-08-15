package org.thanosapollo.nema.ui.theme

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PersistableBackgroundAccess {
    fun takeReadPermission(uri: Uri): Boolean

    fun isReadable(uri: Uri): Boolean

    fun releaseReadPermission(uri: Uri) = Unit
}

class ContentResolverBackgroundAccess(
    private val resolver: ContentResolver,
) : PersistableBackgroundAccess {
    override fun takeReadPermission(uri: Uri): Boolean {
        var permissionTaken = false
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            permissionTaken = true
            val readable = isReadable(uri) && resolver.openFileDescriptor(uri, "r")?.use { true } == true
            if (!readable) releaseReadPermission(uri)
            readable
        } catch (_: SecurityException) {
            if (permissionTaken) releaseReadPermission(uri)
            false
        } catch (_: IOException) {
            if (permissionTaken) releaseReadPermission(uri)
            false
        }
    }

    override fun isReadable(uri: Uri): Boolean = resolver.persistedUriPermissions.any { permission ->
        permission.isReadPermission && permission.uri == uri
    }

    override fun releaseReadPermission(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Permission was already revoked.
        }
    }
}

class AppearanceRepository(
    private val preferences: SharedPreferences,
    private val backgroundAccess: PersistableBackgroundAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writes = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun stored(scope: AppearanceScope): AppearanceSpec? = read(scope)

    fun resolve(accountId: String?, canonicalBarePeer: String?): AppearanceSpec {
        val app = read(AppearanceScope.App) ?: AppearanceSpec.DEFAULT
        val account = accountId?.let { read(AppearanceScope.Account(it)) }
        val conversation = if (accountId != null && canonicalBarePeer != null) {
            read(AppearanceScope.Conversation(accountId, canonicalBarePeer))
        } else {
            null
        }
        return sanitizeBackground(resolveAppearance(app, account, conversation))
    }

    fun baseFor(scope: AppearanceScope): AppearanceSpec = read(scope)?.let(::sanitizeBackground) ?: when (scope) {
        AppearanceScope.App -> AppearanceSpec.DEFAULT
        is AppearanceScope.Account -> resolve(null, null)
        is AppearanceScope.Conversation -> resolve(scope.accountId, null)
    }

    suspend fun save(scope: AppearanceScope, spec: AppearanceSpec): Boolean = writes.withLock {
        persist(scope, spec)
    }

    suspend fun update(
        scope: AppearanceScope,
        transform: (AppearanceSpec) -> AppearanceSpec,
    ): Boolean = writes.withLock {
        persist(scope, transform(baseFor(scope)))
    }

    suspend fun clear(scope: AppearanceScope): Boolean = writes.withLock {
        withContext(ioDispatcher) {
            val prefix = prefix(scope)
            val oldBackground = read(scope)?.backgroundUri
            val success = preferences.edit()
                .remove("$prefix.present")
                .remove("$prefix.mode")
                .remove("$prefix.palette")
                .remove("$prefix.primary")
                .remove("$prefix.background")
                .remove("$prefix.image")
                .remove("$prefix.textScale")
                .remove("$prefix.uiScale")
                .commit()
            if (success) {
                mutableRevision.update { it + 1 }
                releaseIfUnused(oldBackground)
            }
            success
        }
    }

    suspend fun setBackground(scope: AppearanceScope, uri: Uri): Boolean = writes.withLock {
        val permitted = withContext(ioDispatcher) {
            backgroundAccess.takeReadPermission(uri) && backgroundAccess.isReadable(uri)
        }
        if (!permitted) return@withLock false
        val success = persist(scope, baseFor(scope).copy(backgroundUri = uri.toString()))
        if (!success) withContext(ioDispatcher) { releaseIfUnused(uri.toString()) }
        success
    }

    suspend fun clearBackground(scope: AppearanceScope): Boolean = writes.withLock {
        persist(scope, baseFor(scope).copy(backgroundUri = null))
    }

    private suspend fun persist(scope: AppearanceScope, spec: AppearanceSpec): Boolean =
        withContext(ioDispatcher) {
            val prefix = prefix(scope)
            val oldBackground = read(scope)?.backgroundUri
            val sanitized = spec.sanitized()
            val editor = preferences.edit()
                .putBoolean("$prefix.present", true)
                .putString("$prefix.mode", sanitized.themeMode.name)
                .putString("$prefix.image", sanitized.backgroundUri)
                .putFloat("$prefix.textScale", sanitized.textScale)
                .putFloat("$prefix.uiScale", sanitized.uiScale)
            when (val palette = spec.palette) {
                PaletteChoice.Neutral -> editor
                    .putString("$prefix.palette", NEUTRAL)
                    .remove("$prefix.primary")
                    .remove("$prefix.background")
                is PaletteChoice.Custom -> editor
                    .putString("$prefix.palette", CUSTOM)
                    .putLong("$prefix.primary", palette.primary.toLong())
                    .putLong("$prefix.background", palette.background.toLong())
            }
            val success = editor.commit()
            if (success) {
                mutableRevision.update { it + 1 }
                if (oldBackground != spec.backgroundUri) releaseIfUnused(oldBackground)
            }
            success
        }

    private fun read(scope: AppearanceScope): AppearanceSpec? {
        val prefix = prefix(scope)
        if (!preferences.getBoolean("$prefix.present", false)) return null
        val mode = preferences.getString("$prefix.mode", null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        val palette = if (preferences.getString("$prefix.palette", NEUTRAL) == CUSTOM) {
            val primary = preferences.getLong("$prefix.primary", Long.MIN_VALUE)
            val background = preferences.getLong("$prefix.background", Long.MIN_VALUE)
            if (primary == Long.MIN_VALUE || background == Long.MIN_VALUE) {
                PaletteChoice.Neutral
            } else {
                PaletteChoice.Custom.create(primary.toInt(), background.toInt()) ?: PaletteChoice.Neutral
            }
        } else {
            PaletteChoice.Neutral
        }
        return AppearanceSpec(
            themeMode = mode,
            palette = palette,
            backgroundUri = preferences.getString("$prefix.image", null),
            textScale = sanitizeScale(
                preferences.getFloat("$prefix.textScale", 1f),
                MIN_TEXT_SCALE,
                MAX_TEXT_SCALE,
            ),
            uiScale = sanitizeScale(
                preferences.getFloat("$prefix.uiScale", 1f),
                MIN_UI_SCALE,
                MAX_UI_SCALE,
            ),
        )
    }

    private fun sanitizeBackground(spec: AppearanceSpec): AppearanceSpec {
        val uri = spec.backgroundUri?.let(Uri::parse) ?: return spec
        return if (backgroundAccess.isReadable(uri)) spec else spec.copy(backgroundUri = null)
    }

    private fun releaseIfUnused(uri: String?) {
        if (uri == null) return
        val stillUsed = preferences.all.any { (key, value) ->
            key.endsWith(".image") && value == uri
        }
        if (!stillUsed) backgroundAccess.releaseReadPermission(Uri.parse(uri))
    }

    private fun prefix(scope: AppearanceScope): String = when (scope) {
        AppearanceScope.App -> "appearance.app"
        is AppearanceScope.Account -> "appearance.account.${encoded(scope.accountId)}"
        is AppearanceScope.Conversation ->
            "appearance.conversation.${encoded(scope.accountId)}.${encoded(scope.canonicalBarePeer)}"
    }

    private fun encoded(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
    )

    companion object {
        private const val PREFERENCES = "appearance"
        private const val NEUTRAL = "neutral"
        private const val CUSTOM = "custom"

        fun create(context: Context): AppearanceRepository {
            val applicationContext = context.applicationContext
            return AppearanceRepository(
                preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
                backgroundAccess = ContentResolverBackgroundAccess(applicationContext.contentResolver),
            )
        }
    }
}
