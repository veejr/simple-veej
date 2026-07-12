package org.veejr.android

import java.security.MessageDigest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.Envelope
import org.veejr.core.network.SenderSummary
import org.veejr.core.network.Recipient

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

    @Test
    fun `opens a protocol v1 message payload after consent`() {
        val coordinator = IdentityCoordinator()
        val prepared = coordinator.prepare("correct horse".toCharArray())
        val publicKey = java.util.Base64.getDecoder().decode(prepared.request.publicKey)
        val sealed = VeejrCrypto().sealBox(
            """{"v":1,"kind":"message","text":"hello from web"}""".toByteArray(),
            publicKey,
            prepared.secretKey,
        )
        val envelope = Envelope(
            publicId = "opaque",
            batchId = "batch",
            kind = "message",
            ciphertext = java.util.Base64.getEncoder().encodeToString(sealed.ciphertext),
            nonce = java.util.Base64.getEncoder().encodeToString(sealed.nonce),
            peerKey = prepared.request.publicKey,
            sender = SenderSummary("42", "@alice"),
            sentByMe = true,
            resealed = false,
            createdAt = "2026-07-12T20:00:00Z",
            displayCount = 0,
        )

        assertTrue(coordinator.openMessage(envelope, prepared.secretKey) == "hello from web")
        prepared.secretKey.fill(0)
    }

    @Test
    fun `seals independent recipient and self copies`() {
        val coordinator = IdentityCoordinator()
        val sender = coordinator.prepare("sender passphrase".toCharArray())
        val recipient = coordinator.prepare("recipient passphrase".toCharArray())
        val recipients = listOf(
            Recipient("7", "bob", "@bob", recipient.request.publicKey),
            Recipient("42", "alice", "@alice", sender.request.publicKey),
        )

        val copies = coordinator.sealMessage("hello bob", recipients, sender.secretKey)

        assertTrue(copies.map { it.recipientId }.toSet() == setOf("7", "42"))
        val bobCopy = copies.first { it.recipientId == "7" }
        val plaintext = VeejrCrypto().openBox(
            java.util.Base64.getDecoder().decode(bobCopy.ciphertext),
            java.util.Base64.getDecoder().decode(bobCopy.nonce),
            java.util.Base64.getDecoder().decode(sender.request.publicKey),
            recipient.secretKey,
        )
        assertTrue(checkNotNull(plaintext).toString(Charsets.UTF_8).contains("hello bob"))
        plaintext.fill(0)
        sender.secretKey.fill(0)
        recipient.secretKey.fill(0)
    }
}
