package org.veejr.simple

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.veejr.core.crypto.VeejrCrypto

/**
 * The plaintext of one sealed call signal (client protocol v1, section 26.4).
 * Kinds this app does not act on — browser screen-share state, for example —
 * decode to [Other] and are ignored.
 */
sealed interface CallSignal {
    data class Offer(val sdp: String) : CallSignal
    data class Answer(val sdp: String) : CallSignal
    data class Ice(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int) : CallSignal
    data class MediaState(val audio: Boolean, val video: Boolean) : CallSignal
    data object Other : CallSignal

    fun toJson(): JsonObject = when (this) {
        is Offer -> buildJsonObject {
            put("kind", "offer")
            put("sdp", sdp)
        }
        is Answer -> buildJsonObject {
            put("kind", "answer")
            put("sdp", sdp)
        }
        is Ice -> buildJsonObject {
            put("kind", "ice")
            put(
                "candidate",
                buildJsonObject {
                    put("candidate", candidate)
                    put("sdpMid", sdpMid)
                    put("sdpMLineIndex", sdpMLineIndex)
                },
            )
        }
        is MediaState -> buildJsonObject {
            put("kind", "media_state")
            put("audio", audio)
            put("video", video)
        }
        Other -> error("Other is receive-only")
    }

    companion object {
        fun fromJson(json: JsonObject): CallSignal? = runCatching {
            when (json["kind"]?.jsonPrimitive?.contentOrNull) {
                "offer" -> Offer(json.string("sdp"))
                "answer" -> Answer(json.string("sdp"))
                "ice" -> {
                    val candidate = json["candidate"]!!.jsonObject
                    Ice(
                        candidate = candidate.string("candidate"),
                        sdpMid = candidate["sdpMid"]?.jsonPrimitive?.contentOrNull,
                        sdpMLineIndex = candidate["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                    )
                }
                "media_state" -> MediaState(
                    audio = json["audio"]?.jsonPrimitive?.boolean ?: true,
                    video = json["video"]?.jsonPrimitive?.boolean ?: true,
                )
                null -> null
                else -> Other
            }
        }.getOrNull()

        private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
    }
}

/** A signal sealed for the wire: standard padded Base64, as the browser uses. */
data class SealedSignal(val ciphertext: String, val nonce: String)

/**
 * `nacl.box` sealing between this user's identity and the peer's pinned key,
 * byte-compatible with the browser's `sealFor`/`openFrom`.
 */
class SignalSealer(
    private val crypto: VeejrCrypto,
    private val mySecret: ByteArray,
    private val peerPublicKey: ByteArray,
) {
    fun seal(signal: CallSignal): SealedSignal {
        val sealed = crypto.sealBox(
            signal.toJson().toString().toByteArray(Charsets.UTF_8),
            peerPublicKey,
            mySecret,
        )
        return SealedSignal(
            ciphertext = Base64.getEncoder().encodeToString(sealed.ciphertext),
            nonce = Base64.getEncoder().encodeToString(sealed.nonce),
        )
    }

    /** Opens a signal from the peer; null if it fails authentication or parsing. */
    fun open(sealed: SealedSignal): CallSignal? = runCatching {
        val plain = crypto.openBox(
            Base64.getDecoder().decode(sealed.ciphertext),
            Base64.getDecoder().decode(sealed.nonce),
            peerPublicKey,
            mySecret,
        ) ?: return null
        CallSignal.fromJson(Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject)
    }.getOrNull()

    fun destroy() {
        mySecret.fill(0)
    }
}

/**
 * Perfect negotiation needs the two ends to agree, without talking, on which
 * one yields. The browser makes the side whose id is greater *as a string*
 * polite (`String(localId) > String(id)`); this must match exactly.
 */
fun isPolite(myUserId: String, peerUserId: String): Boolean = myUserId > peerUserId
