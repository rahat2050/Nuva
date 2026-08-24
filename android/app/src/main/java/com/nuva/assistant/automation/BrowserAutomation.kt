package com.nuva.assistant.automation

import android.content.Context

/**
 * Browser automation (roadmap step 13): search + navigate. Platform intents do
 * the heavy lifting; the browser's own search box only needs the URL bar trick.
 */
object BrowserAutomation {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val userReason: String) : Result
    }

    fun searchWeb(context: Context, query: String): Result =
        if (AppLauncher.webSearch(context, query)) Result.Opened else Result.Failed("Browser khulte parini.")

    fun navigate(context: Context, url: String): Result =
        if (AppLauncher.openUrl(context, url)) Result.Opened else Result.Failed("Page ta khulte parini.")
}
