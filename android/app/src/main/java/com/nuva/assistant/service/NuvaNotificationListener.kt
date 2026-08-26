package com.nuva.assistant.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nuva.assistant.core.security.SensitiveAppPolicy

/**
 * Notification reader (v1.1).
 *
 * NUVA summarizes active notifications and, from v2.3, can use an app's
 * official free-form RemoteInput reply action after a blocking confirmation.
 * It never fabricates a reply route, never replies to financial apps and never
 * dismisses notifications. Apps that expose no RemoteInput remain unsupported.
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

    data class ReplyHandle(
        val title: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
    )

    data class NuvaNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
        val mediaSessionToken: android.media.session.MediaSession.Token? = null,
        val replyHandle: ReplyHandle? = null,
    )

    private fun parse(sbn: StatusBarNotification): NuvaNotification? {
        if (sbn.packageName == applicationContext.packageName) return null
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        val token = mediaToken(extras)
        val replyHandle = findReplyHandle(sbn.notification?.actions)
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
            replyHandle = replyHandle,
        )
    }

    private fun findReplyHandle(actions: Array<android.app.Notification.Action>?): ReplyHandle? {
        val candidates = actions.orEmpty().mapNotNull { action ->
            val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray()
            val pending = action.actionIntent
            if (inputs.isEmpty() || pending == null) null
            else ReplyHandle(action.title?.toString().orEmpty(), pending, inputs)
        }
        val preferred = preferredReplyIndex(candidates.map { it.title }) ?: return null
        return candidates[preferred]
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

    sealed interface ReplyResult {
        data class Sent(val appLabel: String) : ReplyResult
        data object NeedsAccess : ReplyResult
        data object NotificationMissing : ReplyResult
        data object ReplyUnavailable : ReplyResult
        data object SensitiveBlocked : ReplyResult
        data class Failed(val reason: String) : ReplyResult
    }

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

        /** Newest-safe notifications for OPEN_NOTIFICATION_APP (banking skipped). */
        fun safeSnapshot(limit: Int = 10): List<NuvaNotification> {
            if (!isConnected) return emptyList()
            val items: List<NuvaNotification>
            synchronized(store) { items = store.toList() }
            return items.filter { !com.nuva.assistant.core.security.SensitiveAppPolicy.isSensitivePackage(it.packageName) }
                .take(limit)
        }

        /** Selects an already-valid free-form action, preferring an explicit Reply label. */
        fun preferredReplyIndex(titles: List<String>): Int? {
            if (titles.isEmpty()) return null
            val explicit = titles.indexOfFirst { title ->
                listOf("reply", "respond", "উত্তর", "রিপ্লাই").any { marker ->
                    title.contains(marker, ignoreCase = true)
                }
            }
            return if (explicit >= 0) explicit else 0
        }

        /** Official RemoteInput only; blocking confirmation happens in CommandExecutor. */
        fun reply(ordinal: Int, message: String): ReplyResult {
            val service = companionInstance ?: return ReplyResult.NeedsAccess
            if (SensitiveAppPolicy.mentionsCredentials(message)) return ReplyResult.SensitiveBlocked
            val notification = safeSnapshot(limit = 30).getOrNull(ordinal.coerceIn(1, 30) - 1)
                ?: return ReplyResult.NotificationMissing
            if (SensitiveAppPolicy.isSensitivePackage(notification.packageName)) return ReplyResult.SensitiveBlocked
            val handle = notification.replyHandle ?: return ReplyResult.ReplyUnavailable
            return try {
                val fillIn = Intent()
                val results = Bundle()
                handle.remoteInputs.forEach { input -> results.putCharSequence(input.resultKey, message.take(1_000)) }
                RemoteInput.addResultsToIntent(handle.remoteInputs, fillIn, results)
                handle.pendingIntent.send(service, 0, fillIn)
                ReplyResult.Sent(notification.appLabel)
            } catch (error: PendingIntent.CanceledException) {
                ReplyResult.Failed("Reply action expire hoye geche.")
            } catch (error: Exception) {
                ReplyResult.Failed(error.message ?: "RemoteInput failed")
            }
        }

        fun canReply(ordinal: Int = 1): Boolean =
            safeSnapshot(limit = 30).getOrNull(ordinal.coerceIn(1, 30) - 1)?.replyHandle != null

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
