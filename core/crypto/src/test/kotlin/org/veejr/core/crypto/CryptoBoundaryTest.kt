package org.veejr.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoBoundaryTest {
    @Test
    fun `matches protocol v1 key wrapping parameters`() {
        assertEquals(310_000, CryptoBoundary.PBKDF2_ITERATIONS)
        assertEquals(32, CryptoBoundary.IDENTITY_KEY_BYTES)
        assertEquals(32, CryptoBoundary.SECRETBOX_KEY_BYTES)
        assertEquals(24, CryptoBoundary.NONCE_BYTES)
        assertEquals(16, CryptoBoundary.WRAP_SALT_BYTES)
    }
}
