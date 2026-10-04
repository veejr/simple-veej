package org.veejr.simple

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoenixSocketTest {
    private val empty = JsonObject(emptyMap())

    @Test
    fun `disconnect returns null to callers and rejoins automatically`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        factory.latest.open()
        runCurrent()
        factory.latest.join()
        runCurrent()
        assertNotNull(socket.joined.value)
        val reply = async { socket.push("start", empty) }
        runCurrent()
        factory.latest.fail()
        runCurrent()
        assertNull(reply.await()) // must not cancel the caller's calling flow
        assertNull(socket.joined.value)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, factory.sockets.size)
        factory.latest.open()
        runCurrent()
        factory.latest.join()
        runCurrent()
        assertNotNull(socket.joined.value)
        socket.stop()
    }

    @Test
    fun `stalled join times out and late replies cannot revive it`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        val old = factory.latest
        old.open()
        runCurrent()
        assertNull(socket.push("start", empty))
        advanceTimeBy(11_000)
        runCurrent()
        assertTrue(old.cancelled)
        assertEquals(2, factory.sockets.size)
        old.join()
        runCurrent()
        assertNull(socket.joined.value)
        socket.stop()
    }

    @Test
    fun `both unauthorized upgrade statuses request a refreshed URL`() = runTest {
        for (code in listOf(401, 403)) {
            val factory = FakeFactory()
            val refreshes = mutableListOf<Boolean>()
            val socket = PhoenixSocket(backgroundScope, factory, TOPIC) {
                refreshes += it
                URL
            }
            socket.start()
            runCurrent()
            factory.latest.fail(code)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(false, true), refreshes)
            socket.stop()
            runCurrent()
        }
    }

    @Test
    fun `temporary credential lookup failure retries without losing the loop`() = runTest {
        val factory = FakeFactory()
        var lookups = 0
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) {
            if (++lookups == 1) throw IOException("offline")
            URL
        }
        socket.start()
        runCurrent()
        assertTrue(factory.sockets.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, factory.sockets.size)
        socket.stop()
    }

    @Test
    fun `missing heartbeat reply reconnects while acknowledged heartbeats keep the socket`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        val first = factory.latest
        first.open()
        runCurrent()
        first.join()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        first.reply(first.sent.last())
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, factory.sockets.size)
        advanceTimeBy(30_500)
        runCurrent()
        assertTrue(first.cancelled)
        assertEquals(2, factory.sockets.size)
        socket.stop()
    }

    @Test
    fun `stop cancels timers pending requests and ignores callbacks after restart`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        val first = factory.latest
        first.open()
        runCurrent()
        first.join()
        runCurrent()
        val reply = async { socket.push("accept", empty) }
        runCurrent()
        socket.stop()
        assertNull(reply.await())
        val sent = first.sent.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(sent, first.sent.size)
        assertEquals(1, factory.sockets.size)
        socket.start()
        runCurrent()
        factory.latest.open()
        runCurrent()
        factory.latest.join()
        runCurrent()
        first.fail()
        runCurrent()
        assertNotNull(socket.joined.value)
        socket.stop()
    }

    @Test
    fun `failed sends and reply timeouts return null without cancelling callers`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        factory.latest.open()
        runCurrent()
        factory.latest.join()
        runCurrent()
        val timeout = async { socket.push("start", empty, timeoutMs = 100) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(timeout.await())
        factory.latest.acceptSends = false
        assertNull(socket.push("start", empty))
        socket.stop()
    }

    @Test
    fun `valid push reply succeeds and server channel errors trigger reconnect`() = runTest {
        val factory = FakeFactory()
        val socket = PhoenixSocket(backgroundScope, factory, TOPIC) { URL }
        socket.start()
        runCurrent()
        factory.latest.open()
        runCurrent()
        factory.latest.join()
        runCurrent()
        val reply = async { socket.push("start", empty) }
        runCurrent()
        factory.latest.reply(factory.latest.sent.last())
        runCurrent()
        assertTrue(reply.await()!!.ok)
        factory.latest.message(PhoenixFrame(factory.latest.sent.first().ref, null, TOPIC, "phx_error", empty))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, factory.sockets.size)
        socket.stop()
    }

    private class FakeFactory : WebSocket.Factory {
        val sockets = mutableListOf<FakeSocket>()
        val latest get() = sockets.last()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also { sockets += it }
    }

    private class FakeSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<PhoenixFrame>()
        var cancelled = false
        var acceptSends = true
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            sent += checkNotNull(PhoenixFrames.decode(text))
            return acceptSends
        }
        override fun send(bytes: ByteString) = acceptSends
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(this, response(101))
        fun join() = reply(sent.first())
        fun reply(frame: PhoenixFrame) = message(PhoenixFrame(
            frame.joinRef, frame.ref, frame.topic, "phx_reply",
            buildJsonObject { put("status", "ok"); put("response", JsonObject(emptyMap())) },
        ))
        fun message(frame: PhoenixFrame) = listener.onMessage(this, PhoenixFrames.encode(frame))
        fun fail(code: Int? = null) = listener.onFailure(this, IOException("disconnected"), code?.let(::response))
        private fun response(code: Int) = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(code).message("test").build()
    }

    private companion object {
        const val TOPIC = "calls:v1"
        const val URL = "wss://example.test/api/v1/socket/websocket?access_token=test"
    }
}
