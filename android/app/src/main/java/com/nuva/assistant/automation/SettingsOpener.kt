package com.nuva.assistant.automation

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import com.nuva.assistant.command.SettingTarget

/**
 * System settings (v1.1). Android deliberately restricts third-party apps
 * from toggling most global settings, so NUVA's policy is:
 *
 *  * TORCH — safe, reversible, unrestricted: toggled directly via CameraManager.
 *  * everything else — opens the exact settings screen (or the API 29+ panel)
 *    and tells the user to flip the switch. No silent changes, no hacks
 *    (policy §27).
 *
 * DND is toggled directly ONLY when the user already granted NUVA
 * "Do Not Disturb access" manually in system settings.
 */
object SettingsOpener {

    @Volatile
    private var torchOn = false

    sealed interface Result {
        /** Action done directly (torch) or screen/panel opened. */
        data object Done : Result
        /** Needs a manual step in the screen that just opened. */
        data class ManualStep(val speech: String) : Result
        data class Failed(val userReason: String) : Result
    }

    fun open(context: Context, target: SettingTarget): Result = when (target) {
        SettingTarget.TORCH -> toggleTorch(context)
        SettingTarget.BRIGHTNESS -> start(context, Settings.ACTION_DISPLAY_SETTINGS, "Brightness setting khule dicchi.")
        SettingTarget.VOLUME -> start(context, Settings.ACTION_SOUND_SETTINGS, "Sound o volume setting khule dicchi.")
        SettingTarget.DND -> dnd(context)
        SettingTarget.WIFI -> wifi(context)
        SettingTarget.BLUETOOTH -> start(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth setting khule dicchi.")
        SettingTarget.GENERAL_SETTINGS -> start(context, Settings.ACTION_SETTINGS, "Settings khule dicchi.")
        SettingTarget.NOTIFICATION_SETTINGS -> try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.ManualStep("NUVA-র notification settings khulchi.")
        } catch (err: Exception) {
            start(context, "android.settings.NOTIFICATION_SETTINGS", "Notification settings khulchi.")
        }

        SettingTarget.APP_SETTINGS -> try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.ManualStep("NUVA-র app settings khulchi.")
        } catch (err: Exception) {
            start(context, Settings.ACTION_SETTINGS, null)
        }

        SettingTarget.ACCESSIBILITY_SETTINGS -> start(
            context,
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "Accessibility settings khulchi.",
        )
        SettingTarget.MOBILE_DATA -> start(context, Settings.ACTION_DATA_USAGE_SETTINGS, "Mobile data settings khulchi.")
        SettingTarget.AIRPLANE_MODE -> start(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode settings khulchi.")
        SettingTarget.LOCATION -> start(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location settings khulchi.")
        SettingTarget.HOTSPOT -> start(context, "android.settings.TETHER_SETTINGS", "Hotspot/tether settings khulchi.")
        SettingTarget.NFC -> start(context, Settings.ACTION_NFC_SETTINGS, "NFC settings khulchi.")
        SettingTarget.VPN -> start(context, Settings.ACTION_VPN_SETTINGS, "VPN settings khulchi.")
        SettingTarget.BATTERY_SAVER -> start(context, Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery saver settings khulchi.")
        SettingTarget.DEFAULT_APPS -> start(context, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Default apps settings khulchi.")
        SettingTarget.DATE_TIME -> start(context, Settings.ACTION_DATE_SETTINGS, "Date and time settings khulchi.")
        SettingTarget.LANGUAGE -> start(context, Settings.ACTION_LOCALE_SETTINGS, "Language settings khulchi.")
        SettingTarget.STORAGE_SETTINGS -> start(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage settings khulchi.")
        SettingTarget.PRIVACY -> start(context, "android.settings.PRIVACY_SETTINGS", "Privacy settings khulchi.")
        SettingTarget.SECURITY -> start(context, Settings.ACTION_SECURITY_SETTINGS, "Security settings khulchi.")
        SettingTarget.CAST -> start(context, "android.settings.CAST_SETTINGS", "Cast settings khulchi.")
        SettingTarget.PRINT -> start(context, Settings.ACTION_PRINT_SETTINGS, "Print settings khulchi.")
        SettingTarget.CAPTIONS -> start(context, Settings.ACTION_CAPTIONING_SETTINGS, "Caption settings khulchi.")
        SettingTarget.EMERGENCY_INFO -> when (
            val result = start(
                context,
                "android.settings.EMERGENCY_ASSISTANCE_SETTINGS",
                "Emergency information settings khulchi.",
            )
        ) {
            is Result.Failed -> start(context, Settings.ACTION_SECURITY_SETTINGS, "Emergency screen paini; security settings khulchi.")
            else -> result
        }
    }

    // --- Torch --------------------------------------------------------------------

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            torchOn = enabled
        }
    }

    fun toggleTorch(context: Context): Result {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result.Failed("Ei phone e flashlight nai bole mone hocche.")
            manager.registerTorchCallback(torchCallback, null)
            val next = !torchOn
            manager.setTorchMode(id, next)
            Result.Done
        } catch (err: Exception) {
            Result.Failed("Torch jolate parini — camera bebohar hoye ache hote pare.")
        }
    }

    // --- DND ----------------------------------------------------------------------

    private fun dnd(context: Context): Result {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Result.Failed("Do Not Disturb control korte parini.")
        return if (nm.isNotificationPolicyAccessGranted) {
            try {
                val next = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                nm.setInterruptionFilter(
                    if (next) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL,
                )
                Result.Done
            } catch (err: Exception) {
                start(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb setting khule dicchi.")
            }
        } else {
            start(
                context,
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                "DND NUVA sorasori badhate pare na — setting ta khule dicchi, ekbar access dile por theke parbo.",
            )
        }
    }

    // --- Wi-Fi ----------------------------------------------------------------------

    private fun wifi(context: Context): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.startActivity(
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Result.ManualStep("Wifi o data panel khule dicchi — on/off korun.")
            } catch (err: Exception) {
                start(context, Settings.ACTION_WIFI_SETTINGS, "Wifi setting khule dicchi.")
            }
        }
        return start(context, Settings.ACTION_WIFI_SETTINGS, "Wifi setting khule dicchi — on/off apni korun.")
    }

    // --- Shared ----------------------------------------------------------------------

    private fun start(context: Context, action: String, manualSpeech: String?): Result = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (manualSpeech != null) Result.ManualStep(manualSpeech) else Result.Done
    } catch (err: Exception) {
        Result.Failed("Oi setting screen ta khulte parini.")
    }
}
