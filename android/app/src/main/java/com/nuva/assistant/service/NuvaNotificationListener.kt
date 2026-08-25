package com.nuva.assistant.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nuva.assistant.core.security.SensitiveAppPolicy

/**
 * Notification reader (v1.1).
 *
 * READ-ONLY: NUVA summarizes the active notifications when asked. It never
 * replies, dismisses or acts on them — a reliable RemoteInput reply needs
 * per-app integration, so that flow is explicitly unsupported (the UI says
 * so) instead of pretending.
 *
 * Safety built in:
 *  * OTP/PIN-like codes are redacted from every summary (policy §33).
 *  * Notifications from banking/payment apps are NEVER read at all — the
 *    whole summary refuses when the posting app is on the denylist.
 *  * The service only works after the user enables "Notification access"
 *    manually in system settings; NUVA can never enable it itself.
 */
class NuvaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connect(this)
        refresh(activeNotifications?.toList() ?: emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { upsert(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { remove(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        companionInstance = null
    }

    private fun upsert(sbn: StatusBarNotification) {
        val parsed = parse(sbn) ?: return
        synchronized(store) {
            store.removeAll { it.key == parsed.key }
            store.add(0, parsed)
            if (store.size > MAX_STORED) store.removeAt(store.size - 1)
        }
    }

    private fun remove(sbn: StatusBarNotification) {
        val key = "${sbn.packageName}:${sbn.key ?: sbn.id}"
        synchronized(store) { store.removeAll { it.key == key } }
    }

    private fun refresh(list: List<StatusBarNotification>) {
        synchronized(store) {
            store.clear()
            list.forEach { parse(it)?.let { parsed -> store.add(parsed) } }
        }
    }

    data class NuvaNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
        val mediaSessionToken: android.media.session.MediaSession.Token? = null,
    )

    private fun parse(sbn: StatusBarNotification): NuvaNotification? {
        if (sbn.packageName == applicationContext.packageName) return null
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        val token = mediaToken(extras)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        return NuvaNotification(
            key = "${sbn.packageName}:${sbn.key ?: sbn.id}",
            packageName = sbn.packageName,
            appLabel = label,
            title = title,
            text = text,
            postedAt = sbn.postTime,
            mediaSessionToken = token,
        )
    }

    /** MediaSession token of the current media notification, for MEDIA_CONTROL. */
    private fun mediaToken(extras: android.os.Bundle): android.media.session.MediaSession.Token? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable("android.mediaSession", android.media.session.MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable("android.mediaSession")
        }
    }.getOrNull()

    companion object {
        private const val MAX_STORED = 30
        private const val MAX_SUMMARY_ITEMS = 12

        @Volatile
        private var companionInstance: NuvaNotificationListener? = null

        private val store: MutableList<NuvaNotification> = mutableListOf()

        val isConnected: Boolean get() = companionInstance != null

        fun connect(listener: NuvaNotificationListener) {
            companionInstance = listener
        }

        /**
         * Human/voice summary of active notifications. Banking/payment
         * notifications are skipped entirely; OTP-like digits redacted.
         */
        fun summary(): Summary {
            if (!isConnected) return Summary.NeedsAccess
            val items: List<NuvaNotification>
            synchronized(store) { items = store.toList() }
            val safe = items.filter { !SensitiveAppPolicy.isSensitivePackage(it.packageName) }
            if (safe.isEmpty()) {
                return Summary.Empty("Kono nota notification nai." + if (items.size > safe.size) " (kichu sensitive app er notification porbo na.)" else "")
            }
            val lines = safe.take(MAX_SUMMARY_ITEMS).joinToString(". ") { n ->
                val body = listOfNotNull(n.title, n.text?.let { SensitiveAppPolicy.redactCodes(it) })
                    .joinToString(": ")
                "${n.appLabel} — ${body.take(140)}"
            }
            val extra = if (safe.size > MAX_SUMMARY_ITEMS) " ... ar ${safe.size - MAX_SUMMARY_ITEMS} ta." else ""
            return Summary.Ready("$lines$extra")
        }

        fun openAccessSettings(context: Context): Boolean = try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (err: Exception) {
            false
        }

        /**
         * The newest active MediaSession token (from the media notification),
         * used by MEDIA_CONTROL. Requires notification access.
         */
        fun activeMediaSessionToken(): android.media.session.MediaSession.Token? {
            if (!isConnected) return null
            synchronized(store) {
                return store.firstOrNull { it.mediaSessionToken != null }?.mediaSessionToken
            }
        }
    }

    sealed interface Summary {
        data class Ready(val text: String) : Summary
        data class Empty(val text: String) : Summary
        data object NeedsAccess : Summary
    }
}
