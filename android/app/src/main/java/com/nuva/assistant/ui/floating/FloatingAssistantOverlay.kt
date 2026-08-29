package com.nuva.assistant.ui.floating

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.nuva.assistant.core.permissions.NuvaPermissions
import kotlin.math.roundToInt

/**
 * Minimal system-assistant style popup shown above other apps after "Hey Nuva".
 *
 * It is intentionally small and non-blocking: the overlay consumes touch only
 * inside its own bounds, has an explicit close button, and auto-dismisses after
 * terminal success/error states. Medium/high-risk actions render Confirm/Cancel
 * buttons in the same small surface, keeping confirmation explicit without
 * opening the NUVA app.
 */
class FloatingAssistantOverlay(private val context: Context) {

    enum class PopupState {
        IDLE,
        LISTENING,
        PROCESSING,
        EXECUTING,
        CONFIRMATION_REQUIRED,
        SUCCESS,
        ERROR,
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var autoDismiss: Runnable? = null

    fun showStatus(
        state: PopupState,
        title: String,
        detail: String? = null,
        autoDismissMs: Long? = null,
        onDismiss: () -> Unit = {},
    ) {
        handler.post {
            if (!NuvaPermissions.canDrawOverlays(context)) {
                Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
                return@post
            }
            val container = ensureRoot(widthDp = 260)
            container.removeAllViews()
            container.setBackground(backgroundFor(state))

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = iconFor(state)
                textSize = 22f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(closeButton(onDismiss), LinearLayout.LayoutParams(34.dp, 34.dp))
            container.addView(row)

            if (!detail.isNullOrBlank()) {
                container.addView(TextView(context).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(0xFFE8EAED.toInt())
                    maxLines = 4
                    setPadding(36.dp, 4.dp, 6.dp, 0)
                })
            }

            scheduleAutoDismiss(autoDismissMs, onDismiss)
        }
    }

    fun showConfirmation(
        title: String,
        message: String,
        confirmLabel: String = "Yes",
        rejectLabel: String = "Cancel",
        onConfirm: () -> Unit,
        onReject: () -> Unit,
    ) {
        handler.post {
            if (!NuvaPermissions.canDrawOverlays(context)) {
                Toast.makeText(context, title, Toast.LENGTH_LONG).show()
                return@post
            }
            val container = ensureRoot(widthDp = 300)
            container.removeAllViews()
            container.setBackground(backgroundFor(PopupState.CONFIRMATION_REQUIRED))

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = iconFor(PopupState.CONFIRMATION_REQUIRED)
                textSize = 22f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            header.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(closeButton(onReject), LinearLayout.LayoutParams(34.dp, 34.dp))
            container.addView(header)

            container.addView(TextView(context).apply {
                text = message
                textSize = 13f
                setTextColor(0xFFE8EAED.toInt())
                maxLines = 6
                setPadding(36.dp, 6.dp, 6.dp, 10.dp)
            })

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.addView(actionButton(rejectLabel, primary = false) {
                dismiss()
                onReject()
            })
            buttons.addView(actionButton(confirmLabel, primary = true) {
                dismiss()
                onConfirm()
            })
            container.addView(buttons)
            scheduleAutoDismiss(null) { }
        }
    }

    fun dismiss() {
        handler.post {
            autoDismiss?.let(handler::removeCallbacks)
            autoDismiss = null
            root?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            root = null
        }
    }

    private fun ensureRoot(widthDp: Int): LinearLayout {
        val existing = root
        if (existing != null) {
            runCatching {
                val params = existing.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.width = minOf(widthDp.dp, context.resources.displayMetrics.widthPixels - 24.dp)
                    windowManager.updateViewLayout(existing, params)
                }
            }
            return existing
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 10.dp, 10.dp)
            elevation = 12.dp.toFloat()
        }
        val panelWidth = minOf(widthDp.dp, context.resources.displayMetrics.widthPixels - 24.dp)
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // Bottom voice plate mirrors the system-assistant mental model and
            // stays reachable without obscuring the current app's main content.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 28.dp
        }
        windowManager.addView(container, params)
        root = container
        return container
    }

    private fun closeButton(onDismiss: () -> Unit): TextView = TextView(context).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(0xFFE8EAED.toInt())
        setOnClickListener {
            dismiss()
            onDismiss()
        }
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 12f
        setAllCaps(false)
        setTextColor(if (primary) Color.WHITE else 0xFFE8EAED.toInt())
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (primary) {
                intArrayOf(0xFF8B7CFF.toInt(), 0xFF2FC9C3.toInt())
            } else {
                intArrayOf(0xFF4B5568.toInt(), 0xFF252C3B.toInt())
            },
        ).apply {
            cornerRadius = 18.dp.toFloat()
            setStroke(1.dp, 0x44FFFFFF)
        }
        setPadding(12.dp, 0, 12.dp, 0)
        setOnClickListener { onClick() }
    }

    private fun scheduleAutoDismiss(autoDismissMs: Long?, onDismiss: () -> Unit) {
        autoDismiss?.let(handler::removeCallbacks)
        autoDismiss = null
        if (autoDismissMs != null && autoDismissMs > 0) {
            val runnable = Runnable {
                dismiss()
                onDismiss()
            }
            autoDismiss = runnable
            handler.postDelayed(runnable, autoDismissMs)
        }
    }

    private fun backgroundFor(state: PopupState): GradientDrawable {
        val base = when (state) {
            PopupState.LISTENING -> 0xFF0F8F88.toInt()
            PopupState.PROCESSING -> 0xFF365EDB.toInt()
            PopupState.EXECUTING -> 0xFF7657DE.toInt()
            PopupState.CONFIRMATION_REQUIRED -> 0xFFE87B22.toInt()
            PopupState.SUCCESS -> 0xFF168A4A.toInt()
            PopupState.ERROR -> 0xFFC4314F.toInt()
            PopupState.IDLE -> 0xFF182038.toInt()
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.blendARGB(base, Color.WHITE, 0.20f),
                base,
                ColorUtils.blendARGB(base, Color.BLACK, 0.28f),
            ),
        ).apply {
            cornerRadius = 24.dp.toFloat()
            setStroke(1.dp, 0x55FFFFFF)
        }
    }

    private fun iconFor(state: PopupState): String = when (state) {
        PopupState.IDLE -> "✦"
        PopupState.LISTENING -> "●"
        PopupState.PROCESSING -> "…"
        PopupState.EXECUTING -> "↗"
        PopupState.CONFIRMATION_REQUIRED -> "?"
        PopupState.SUCCESS -> "✓"
        PopupState.ERROR -> "!"
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).roundToInt()
}
