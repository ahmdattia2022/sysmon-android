package io.ahmed.sysmon.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device credential store. AES-256 via Android Keystore, no network.
 * Holds router password + thresholds. Survives app reinstall? No — intentionally
 * wiped with the app (exclude-from-backup rules point here).
 */
class Preferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Selected router model (io.ahmed.sysmon.service.router.RouterKind name). */
    var routerKind: String
        get() = prefs.getString(K_KIND, "HUAWEI_HG8145V5") ?: "HUAWEI_HG8145V5"
        set(v) { prefs.edit().putString(K_KIND, v).apply() }

    var routerBase: String
        get() = prefs.getString(K_BASE, "https://192.168.100.1") ?: "https://192.168.100.1"
        set(v) { prefs.edit().putString(K_BASE, v).apply() }

    var routerUser: String
        get() = prefs.getString(K_USER, "admin") ?: "admin"
        set(v) { prefs.edit().putString(K_USER, v).apply() }

    var routerPassword: String
        get() = prefs.getString(K_PASS, "") ?: ""
        set(v) { prefs.edit().putString(K_PASS, v).apply() }

    var bundleGb: Int
        get() = prefs.getInt(K_BUNDLE, 150)
        set(v) { prefs.edit().putInt(K_BUNDLE, v).apply() }

    var hourlyMbLimit: Int
        get() = prefs.getInt(K_HOURLY, 600)
        set(v) { prefs.edit().putInt(K_HOURLY, v).apply() }

    var routerHourlyMbLimit: Int
        get() = prefs.getInt(K_RT_HOURLY, 1024)
        set(v) { prefs.edit().putInt(K_RT_HOURLY, v).apply() }

    var routerDailyMbLimit: Int
        get() = prefs.getInt(K_RT_DAILY, 3072)
        set(v) { prefs.edit().putInt(K_RT_DAILY, v).apply() }

    var emailEnabled: Boolean
        get() = prefs.getBoolean(K_EMAIL, false)
        set(v) { prefs.edit().putBoolean(K_EMAIL, v).apply() }

    /**
     * MAC of the phone running this app. Used by control actions to refuse
     * self-throttle / self-block operations that would cut off polling.
     * User enters it once in Settings (or from the Actions tab "Identify me").
     */
    var selfMac: String
        get() = prefs.getString(K_SELF_MAC, "") ?: ""
        set(v) { prefs.edit().putString(K_SELF_MAC, v.trim().lowercase()).apply() }

    /** Whether the user is "logged in" — any poll attempt proceeds iff this is true. */
    var loggedIn: Boolean
        get() = prefs.getBoolean(K_LOGGED, false)
        set(v) { prefs.edit().putBoolean(K_LOGGED, v).apply() }

    /** Cached session cookie value (string after `Cookie=` in the router's Set-Cookie header). */
    var sessionCookie: String
        get() = prefs.getString(K_SESSION, "") ?: ""
        set(v) { prefs.edit().putString(K_SESSION, v).apply() }

    /** Epoch millis when sessionCookie was established. */
    var sessionEstablishedAt: Long
        get() = prefs.getLong(K_SESSION_TS, 0L)
        set(v) { prefs.edit().putLong(K_SESSION_TS, v).apply() }

    fun clearSession() {
        prefs.edit()
            .remove(K_SESSION)
            .remove(K_SESSION_TS)
            .apply()
    }

    companion object {
        private const val K_KIND = "router_kind"
        private const val K_BASE = "router_base"
        private const val K_USER = "router_user"
        private const val K_PASS = "router_pass"
        private const val K_BUNDLE = "bundle_gb"
        private const val K_HOURLY = "hourly_mb"
        private const val K_RT_HOURLY = "router_hourly_mb"
        private const val K_RT_DAILY = "router_daily_mb"
        private const val K_EMAIL = "email_enabled"
        private const val K_SELF_MAC = "self_mac"
        private const val K_LOGGED = "logged_in"
        private const val K_SESSION = "session_cookie"
        private const val K_SESSION_TS = "session_established_at"
    }
}
