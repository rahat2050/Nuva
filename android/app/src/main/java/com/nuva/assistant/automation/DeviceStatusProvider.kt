package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.nuva.assistant.command.DeviceStatusKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local answers for device-status questions (v1.1): battery, clock time,
 * date, network, storage. Pure reads — no confirmation needed (read-only,
 * policy §39) and no data ever leaves the device.
 */
class DeviceStatusProvider(private val contextProvider: () -> Context) {

    fun answer(kind: DeviceStatusKind): String = when (kind) {
        DeviceStatusKind.BATTERY -> battery()
        DeviceStatusKind.TIME -> time()
        DeviceStatusKind.DATE -> date()
        DeviceStatusKind.NETWORK -> network()
        DeviceStatusKind.STORAGE -> storage()
    }

    private fun battery(): String {
        val context = contextProvider()
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status: Intent? = context.registerReceiver(null, ifilter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else queryCapacity(context)
        if (percent < 0) return "Battery status pte parini."
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        return if (charging) "Battery $percent% — ekhon charge hocche." else "Battery $percent% ache."
    }

    private fun queryCapacity(context: Context): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 } ?: -1
    }.getOrDefault(-1)

    private fun time(): String {
        val now = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
        return "Ekhon somoy $now."
    }

    private fun date(): String {
        val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
        return "Aj $today."
    }

    private fun network(): String {
        val context = contextProvider()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Network status pte parini."
        val network = cm.activeNetwork ?: return "Internet nai — network off mone hocche."
        val caps = cm.getNetworkCapabilities(network) ?: return "Internet nai."
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wifi e connected ache."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data on ache."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet e connected."
            else -> "Kono internet connection paina."
        }
    }

    private fun storage(): String {
        return runCatching {
            val dataDir: File = Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            val totalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
            val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
            val usedPct = if (totalGb > 0) ((totalGb - freeGb) / totalGb * 100).toInt() else 0
            "Storage e %.1f GB free, %.0f%% use hoyeche.".format(freeGb, usedPct)
        }.getOrDefault("Storage status pte parini.")
    }
}
