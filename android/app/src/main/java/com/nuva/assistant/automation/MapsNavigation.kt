package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.MapRequestType
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.TravelMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** User-visible maps/navigation handoff; no location is read by NUVA. */
object MapsNavigation {
    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    fun open(context: Context, action: NuvaAction.MapNavigation): Result {
        val uri = when (action.requestType) {
            MapRequestType.NAVIGATION -> if (action.travelMode == TravelMode.TRANSIT) {
                Uri.parse(webUrl(action))
            } else {
                Uri.parse("google.navigation:q=${encode(action.destination)}&mode=${action.travelMode.navigationCode}")
            }
            MapRequestType.NEARBY -> Uri.parse("geo:0,0?q=${encode(action.destination)}")
            MapRequestType.STREET_VIEW -> coordinatePair(action.destination)?.let { (lat, lng) ->
                Uri.parse("google.streetview:cbll=$lat,$lng")
            } ?: Uri.parse("geo:0,0?q=${encode("street view ${action.destination}")}")
            MapRequestType.DIRECTIONS -> Uri.parse(webUrl(action))
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (_: Exception) {
            if (AppLauncher.openUrl(context, webUrl(action))) Result.Opened
            else Result.Failed("Maps ba browser khulte parini.")
        }
    }

    fun webUrl(action: NuvaAction.MapNavigation): String {
        val base = when (action.requestType) {
            MapRequestType.NEARBY -> "https://www.google.com/maps/search/?api=1&query=${encode(action.destination)}"
            MapRequestType.STREET_VIEW -> "https://www.google.com/maps/search/?api=1&query=${encode(action.destination)}"
            MapRequestType.DIRECTIONS,
            MapRequestType.NAVIGATION,
            -> buildString {
                append("https://www.google.com/maps/dir/?api=1")
                action.origin?.let { append("&origin=").append(encode(it)) }
                append("&destination=").append(encode(action.destination))
                append("&travelmode=").append(action.travelMode.wireName)
                if (action.requestType == MapRequestType.NAVIGATION) append("&dir_action=navigate")
            }
        }
        return base
    }

    fun coordinatePair(value: String): Pair<Double, Double>? {
        val match = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""").matchEntire(value)
            ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
