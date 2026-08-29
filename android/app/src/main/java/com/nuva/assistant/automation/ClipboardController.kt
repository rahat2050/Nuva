package com.nuva.assistant.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.nuva.assistant.command.ClipboardOperation
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** Explicit foreground clipboard operations only; no monitoring/background history. */
object ClipboardController {
    sealed interface Result {
        data class Done(val speech: String, val content: String? = null) : Result
        data object Empty : Result
        data object SensitiveBlocked : Result
        data class Failed(val reason: String) : Result
    }

    fun execute(context: Context, action: NuvaAction.ClipboardAction): Result {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return Result.Failed("Clipboard service paini.")
        return try {
            when (action.operation) {
                ClipboardOperation.COPY -> {
                    val text = action.text ?: return Result.Failed("Copy text missing.")
                    if (SensitiveAppPolicy.mentionsCredentials(text) || SensitiveAppPolicy.refusalForText(text) != null) {
                        Result.SensitiveBlocked
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("NUVA copied text", text.take(5_000)))
                        Result.Done("Text clipboard-e copy korechi.")
                    }
                }
                ClipboardOperation.READ -> {
                    val clip = clipboard.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrBlank()) Result.Empty
                    else {
                        val safe = SensitiveAppPolicy.redactCodes(text).take(5_000)
                        if (SensitiveAppPolicy.mentionsCredentials(text)) {
                            Result.SensitiveBlocked
                        } else {
                            Result.Done(if (safe.length <= 600) safe else safe.take(600) + "…", safe)
                        }
                    }
                }
                ClipboardOperation.CLEAR -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    Result.Done("Clipboard clear korechi.")
                }
            }
        } catch (error: Exception) {
            Result.Failed("clipboard operation failed")
        }
    }
}
