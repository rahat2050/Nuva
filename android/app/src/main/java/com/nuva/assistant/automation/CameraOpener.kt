package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.nuva.assistant.command.CaptureMode

/**
 * Camera (v1.2). NUVA opens the camera app in the requested mode. The
 * "take photo" flow launches the system still-capture intent on an EXPLICIT
 * command only — the shutter stays in the user's hands; NUVA never captures
 * anything secretly (it has no CAMERA usage of its own at all).
 */
object CameraOpener {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val userReason: String) : Result
    }

    fun open(context: Context, mode: CaptureMode): Result {
        val intent = when (mode) {
            CaptureMode.PHOTO -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            CaptureMode.VIDEO -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            CaptureMode.CAPTURE -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (err: Exception) {
            // No camera app / no camera — say so honestly.
            Result.Failed("Camera khulte parini — ei phone e camera app ache kina dekhun.")
        }
    }
}
