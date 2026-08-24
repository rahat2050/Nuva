package com.nuva.assistant.core

import android.content.Context
import java.util.UUID

/**
 * Stable, non-PII device id used for rate limiting and /api/devices
 * registration. Random UUID stored in preferences — not ANDROID_ID, not a
 * hardware identifier.
 */
object DeviceId {

    private const val PREFS = "nuva_identity"
    private const val KEY_DEVICE_ID = "device_id"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = "android-" + UUID.randomUUID().toString().substring(0, 13)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
}
