package com.nuva.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nuva.assistant.ui.NuvaApp
import com.nuva.assistant.ui.theme.NuvaTheme

/**
 * Single-activity Compose app. Voice-first home screen; History / Memory /
 * Settings are tabs of the same screen (blueprint §2.3).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NuvaTheme {
                NuvaApp()
            }
        }
    }
}
