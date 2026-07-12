package org.veejr.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun `protocol starts at version one`() {
        assertEquals(1, Protocol.API_VERSION)
        assertEquals(1, Protocol.PAYLOAD_VERSION)
    }
}
