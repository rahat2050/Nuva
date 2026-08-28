package com.nuva.assistant.systemassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/**
 * Small boundary around Android's user-controlled default-assistant setting.
 *
 * NUVA cannot silently replace Gemini/Google. Android owns the picker and the
 * user must select NUVA there. Once selected, the normal assistant gesture or
 * power-button shortcut is routed through [NuvaVoiceInteractionService].
 */
object SystemAssistantController {

    fun isNuvaDefault(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, NuvaVoiceInteractionService::class.java),
        )
    }.getOrDefault(false)

    /**
     * ROLE_ASSISTANT is not requestable on many Android builds, so the stable
     * path is the system's Assist & voice input screen. OEMs that do not expose
     * that exact action fall back to their Default apps screen.
     */
    fun openAssistantPicker(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val chosen = intents.firstOrNull { intent ->
            intent.resolveActivity(context.packageManager) != null
        } ?: return false
        return runCatching {
            chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chosen)
            true
        }.getOrDefault(false)
    }
}
