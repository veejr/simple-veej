package org.veejr.android

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeShellTest {
    @Test
    fun `notes to yourself contains only self-only outgoing items`() {
        val self = ConversationTarget(
            subjectType = "self",
            id = "42",
            title = "Notes to yourself",
            memberHandles = setOf("@alice"),
            initials = "ME",
        )
        val selfOnly = message("self", listOf("@alice"))
        val toFriend = message("friend", listOf("@alice", "@bob"))
        val incoming = message("incoming", listOf("@alice"), sentByMe = false)

        assertEquals(listOf(selfOnly), messagesForConversation(listOf(selfOnly, toFriend, incoming), self))
    }

    @Test
    fun `contact conversation keeps outgoing self copy addressed to that contact`() {
        val bob = ConversationTarget("contact", "7", "@bob", setOf("@bob"))
        val toBob = message("bob", listOf("@alice", "@bob"))
        val selfOnly = message("self", listOf("@alice"))

        assertEquals(listOf(toBob), messagesForConversation(listOf(toBob, selfOnly), bob))
    }

    private fun message(
        id: String,
        recipients: List<String>,
        sentByMe: Boolean = true,
    ) = InboxMessage(
        publicId = id,
        senderHandle = if (sentByMe) "You" else "@bob",
        text = id,
        createdAt = "2026-07-13T12:00:00Z",
        recipientHandles = recipients,
        sentByMe = sentByMe,
    )
}
