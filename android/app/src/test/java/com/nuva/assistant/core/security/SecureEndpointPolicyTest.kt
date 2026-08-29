package com.nuva.assistant.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureEndpointPolicyTest {
    @Test
    fun `normalizes valid HTTPS endpoints`() {
        assertEquals("https://api.example.com/", SecureEndpointPolicy.normalizeRequired("api.example.com"))
        assertEquals(
            "https://api.example.com:8443/base/",
            SecureEndpointPolicy.normalizeRequired("https://API.example.com:8443/base/"),
        )
        assertEquals("", SecureEndpointPolicy.normalizeOptional("  "))
    }

    @Test
    fun `rejects insecure credentialed or token bearing endpoints`() {
        assertNull(SecureEndpointPolicy.normalizeRequired("http://api.example.com"))
        assertNull(SecureEndpointPolicy.normalizeRequired("https://user:pass@api.example.com"))
        assertNull(SecureEndpointPolicy.normalizeRequired("https://api.example.com?token=secret"))
        assertNull(SecureEndpointPolicy.normalizeRequired("https://api.example.com/#secret"))
        assertNull(SecureEndpointPolicy.normalizeRequired("https://api.example.com/../admin"))
        assertNull(SecureEndpointPolicy.normalizeRequired("not a host"))
    }
}
