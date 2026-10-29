package com.saurabh.offgridchat.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saurabh.offgridchat.ui.theme.DefaultAccent
import com.saurabh.offgridchat.ui.theme.OgAccent
import com.saurabh.offgridchat.ui.theme.PresetAccents
import com.saurabh.offgridchat.ui.theme.ThemeMode
import com.saurabh.offgridchat.protocol.ConnectionPolicy
import com.saurabh.offgridchat.ui.theme.customAccent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.DataStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")

@Singleton
class Preferences @Inject constructor(
    @ApplicationContext val context: Context
) {
    companion object {
        val THEME_KEY = stringPreferencesKey("selected_app_theme")
        val ACCENT_KEY = stringPreferencesKey("accent_name")
        val HUE_KEY = floatPreferencesKey("custom_hue")
        const val DEFAULT_HUE = 210f

        // Identity Keys
        val NODE_ID_KEY = stringPreferencesKey("node_id")
        val PUBLIC_KEY_KEY = stringPreferencesKey("public_key")
        val PRIVATE_KEY_KEY = stringPreferencesKey("private_key")

        // Profile Keys
        val USER_NAME_KEY = stringPreferencesKey("user_name")
        val USER_BIO_KEY = stringPreferencesKey("user_bio")
        val ONLINE_STATUS_KEY = booleanPreferencesKey("online_status")
        val READ_RECEIPTS_KEY = booleanPreferencesKey("read_receipts")
        val ALLOW_NEW_CONNECTIONS_KEY = booleanPreferencesKey("allow_new_connections")
        val HIDE_PREVIEWS_KEY = booleanPreferencesKey("hide_previews")

        // Settings Keys
        val AUTO_RECONNECT_KEY = booleanPreferencesKey("auto_reconnect")
        val NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("notifications_enabled")
        val RUN_IN_BACKGROUND_KEY = booleanPreferencesKey("run_in_background")
        val RELAY_MODE_KEY = booleanPreferencesKey("relay_mode")
        val REQUIRE_APPROVAL_KEY = booleanPreferencesKey("require_approval")
    }

    // Identity Flows
    val nodeIdFlow: Flow<String?> = context.DataStore.data.map { it[NODE_ID_KEY] }
    val publicKeyFlow: Flow<String?> = context.DataStore.data.map { it[PUBLIC_KEY_KEY] }
    val privateKeyFlow: Flow<String?> = context.DataStore.data.map { it[PRIVATE_KEY_KEY] }

    val themeFlow: Flow<ThemeMode> = context.DataStore.data.map { p ->
        when (p[THEME_KEY]) {
            ThemeMode.Light.name -> ThemeMode.Light
            ThemeMode.Dark.name -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    val customHueFlow: Flow<Float> = context.DataStore.data.map { it[HUE_KEY] ?: DEFAULT_HUE }

    val accentFlow: Flow<OgAccent> = context.DataStore.data.map { p ->
        val name = p[ACCENT_KEY]
        if (name == "Custom") customAccent(p[HUE_KEY] ?: DEFAULT_HUE)
        else PresetAccents.firstOrNull { it.name == name } ?: DefaultAccent
    }

    // Profile Flows
    val userNameFlow: Flow<String> = context.DataStore.data.map { it[USER_NAME_KEY] ?: "You" }
    val userBioFlow: Flow<String> = context.DataStore.data.map { it[USER_BIO_KEY] ?: "Offline, connect via Bluetooth" }
    val onlineStatusFlow: Flow<Boolean> = context.DataStore.data.map { it[ONLINE_STATUS_KEY] ?: true }
    val readReceiptsFlow: Flow<Boolean> = context.DataStore.data.map { it[READ_RECEIPTS_KEY] ?: true }
    val allowNewConnectionsFlow: Flow<Boolean> = context.DataStore.data.map { it[ALLOW_NEW_CONNECTIONS_KEY] ?: true }
    val hidePreviewsFlow: Flow<Boolean> = context.DataStore.data.map { it[HIDE_PREVIEWS_KEY] ?: false }

    // Settings Flows
    val autoReconnectFlow: Flow<Boolean> = context.DataStore.data.map { it[AUTO_RECONNECT_KEY] ?: true }
    val notificationsEnabledFlow: Flow<Boolean> = context.DataStore.data.map { it[NOTIFICATIONS_ENABLED_KEY] ?: true }
    val runInBackgroundFlow: Flow<Boolean> = context.DataStore.data.map { it[RUN_IN_BACKGROUND_KEY] ?: true }
    val relayModeFlow: Flow<Boolean> = context.DataStore.data.map { it[RELAY_MODE_KEY] ?: true }
    val requireApprovalFlow: Flow<Boolean> = context.DataStore.data.map { it[REQUIRE_APPROVAL_KEY] ?: false }
    val connectionPolicyFlow: Flow<ConnectionPolicy> = context.DataStore.data.map {
        ConnectionPolicy(it[REQUIRE_APPROVAL_KEY] ?: false, it[ALLOW_NEW_CONNECTIONS_KEY] ?: true)
    }

    suspend fun saveIdentity(nodeId: String, publicKey: String, privateKey: String) {
        context.DataStore.edit {
            it[NODE_ID_KEY] = nodeId
            it[PUBLIC_KEY_KEY] = publicKey
            it[PRIVATE_KEY_KEY] = privateKey
        }
    }

    suspend fun saveTheme(theme: ThemeMode) {
        context.DataStore.edit { it[THEME_KEY] = theme.name }
    }

    suspend fun savePreset(accent: OgAccent) {
        context.DataStore.edit { it[ACCENT_KEY] = accent.name }
    }

    suspend fun saveCustom(hue: Float) {
        context.DataStore.edit {
            it[ACCENT_KEY] = "Custom"
            it[HUE_KEY] = hue
        }
    }

    suspend fun saveUserName(name: String) {
        context.DataStore.edit { it[USER_NAME_KEY] = name }
    }

    suspend fun saveUserBio(bio: String) {
        context.DataStore.edit { it[USER_BIO_KEY] = bio }
    }

    suspend fun saveOnlineStatus(enabled: Boolean) {
        context.DataStore.edit { it[ONLINE_STATUS_KEY] = enabled }
    }

    suspend fun saveReadReceipts(enabled: Boolean) {
        context.DataStore.edit { it[READ_RECEIPTS_KEY] = enabled }
    }

    suspend fun saveAllowNewConnections(enabled: Boolean) {
        context.DataStore.edit { it[ALLOW_NEW_CONNECTIONS_KEY] = enabled }
    }

    suspend fun saveHidePreviews(enabled: Boolean) {
        context.DataStore.edit { it[HIDE_PREVIEWS_KEY] = enabled }
    }

    suspend fun saveAutoReconnect(enabled: Boolean) {
        context.DataStore.edit { it[AUTO_RECONNECT_KEY] = enabled }
    }

    suspend fun saveNotificationsEnabled(enabled: Boolean) {
        context.DataStore.edit { it[NOTIFICATIONS_ENABLED_KEY] = enabled }
    }

    suspend fun saveRunInBackground(enabled: Boolean) {
        context.DataStore.edit { it[RUN_IN_BACKGROUND_KEY] = enabled }
    }

    suspend fun saveRelayMode(enabled: Boolean) {
        context.DataStore.edit { it[RELAY_MODE_KEY] = enabled }
    }

    suspend fun saveRequireApproval(enabled: Boolean) {
        context.DataStore.edit { it[REQUIRE_APPROVAL_KEY] = enabled }
    }

}
