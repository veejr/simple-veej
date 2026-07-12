package org.veejr.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointTest {
    @Test
    fun `normalizes an HTTPS instance to API v1`() {
        val endpoint = ApiEndpoint.parse("https://veejr.example/")
        assertEquals("https://veejr.example/api/v1/", endpoint.uri.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects cleartext by default`() {
        ApiEndpoint.parse("http://veejr.example")
    }
}
