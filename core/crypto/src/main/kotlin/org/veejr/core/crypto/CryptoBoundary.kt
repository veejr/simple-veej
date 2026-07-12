package org.veejr.core.crypto

/**
 * Marker for the client-only cryptographic boundary.
 *
 * Concrete NaCl interoperability will be added with shared server fixtures.
 * Passphrases, raw secret keys, and plaintext must never cross into network DTOs.
 */
object CryptoBoundary {
    const val PBKDF2_ITERATIONS = 310_000
    const val IDENTITY_KEY_BYTES = 32
    const val SECRETBOX_KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val WRAP_SALT_BYTES = 16
}
