package org.veejr.android

import java.security.MessageDigest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.veejr.core.crypto.VeejrCrypto

class IdentityCoordinatorTest {
    @Test
    fun `prepared identity unlocks with its passphrase and matches its public key`() {
        val coordinator = IdentityCoordinator()
        val prepared = coordinator.prepare("correct horse".toCharArray())

        val unlocked = coordinator.unlock(
            prepared.request.publicKey,
            prepared.request.wrappedKey,
            "correct horse".toCharArray(),
        )

        assertTrue(MessageDigest.isEqual(prepared.secretKey, unlocked))
        assertTrue(
            MessageDigest.isEqual(
                VeejrCrypto().publicKeyFromSecret(checkNotNull(unlocked)),
                java.util.Base64.getDecoder().decode(prepared.request.publicKey),
            ),
        )
        prepared.secretKey.fill(0)
        unlocked.fill(0)
    }

    @Test
    fun `wrong passphrase cannot unlock the identity`() {
        val coordinator = IdentityCoordinator()
        val prepared = coordinator.prepare("correct horse".toCharArray())

        assertNull(
            coordinator.unlock(
                prepared.request.publicKey,
                prepared.request.wrappedKey,
                "wrong passphrase".toCharArray(),
            ),
        )
        prepared.secretKey.fill(0)
    }
}
