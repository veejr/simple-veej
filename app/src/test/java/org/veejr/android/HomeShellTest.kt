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

    @Test
    fun `conversation timeline places the newest message at the bottom`() {
        val bob = ConversationTarget("contact", "7", "@bob", setOf("@bob"))
        val newest = message("newest", listOf("@alice", "@bob"), "2026-07-13T12:05:00Z")
        val oldest = message("oldest", listOf("@alice", "@bob"), "2026-07-13T12:00:00Z")

        assertEquals(listOf(oldest, newest), conversationTimeline(listOf(newest, oldest), bob))
    }

    @Test
    fun `conversation timeline keeps only the newest 50 messages`() {
        val bob = ConversationTarget("contact", "7", "@bob", setOf("@bob"))
        val messages = (1..51).map { index ->
            message(
                id = "message-$index",
                recipients = listOf("@alice", "@bob"),
                createdAt = "2026-07-13T12:${index.toString().padStart(2, '0')}:00Z",
            )
        }

        val timeline = conversationTimeline(messages.reversed(), bob)

        assertEquals(50, timeline.size)
        assertEquals("message-2", timeline.first().publicId)
        assertEquals("message-51", timeline.last().publicId)
    }

    @Test
    fun `sent message labels omit the readable self copy`() {
        val toBob = message("bob", listOf("@alice", "@bob"))
        val toSelf = message("self", listOf("@alice"))

        assertEquals("To @bob", messageDirectionLabel(toBob, "@alice"))
        assertEquals("To yourself", messageDirectionLabel(toSelf, "@alice"))
    }

    private fun message(
        id: String,
        recipients: List<String>,
        createdAt: String = "2026-07-13T12:00:00Z",
        sentByMe: Boolean = true,
    ) = InboxMessage(
        publicId = id,
        senderHandle = if (sentByMe) "You" else "@bob",
        text = id,
        createdAt = createdAt,
        recipientHandles = recipients,
        sentByMe = sentByMe,
    )
}
