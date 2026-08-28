package com.nuva.assistant.core.security

import java.net.URI

/** HTTPS-only endpoint normalization for any route that may carry auth or user text. */
object SecureEndpointPolicy {

    fun normalizeRequired(raw: String, defaultWhenBlank: String? = null): String? {
        val value = raw.trim().ifBlank { defaultWhenBlank?.trim().orEmpty() }
        if (value.isBlank()) return null
        val candidate = if (value.contains("://")) value else "https://$value"
        return runCatching {
            val parsed = URI(candidate)
            if (parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank()) return null
            if (parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
            if (parsed.port != -1 && parsed.port !in 1..65_535) return null
            if (parsed.path.orEmpty().split('/').any { it == ".." }) return null
            val normalized = parsed.normalize()
            val path = normalized.path.orEmpty().trimEnd('/')
            URI("https", null, normalized.host.lowercase(), normalized.port, path, null, null)
                .toASCIIString()
                .trimEnd('/') + "/"
        }.getOrNull()
    }

    fun normalizeOptional(raw: String): String? =
        if (raw.isBlank()) "" else normalizeRequired(raw)
}
