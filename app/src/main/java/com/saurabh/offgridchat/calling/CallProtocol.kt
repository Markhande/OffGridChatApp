package com.saurabh.offgridchat.calling

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

enum class CallAction { INVITE, ACCEPT, READY, DECLINE, BUSY, END }

object CallProtocol {
    const val SAMPLE_RATE = 8_000
    const val FRAME_BYTES = 1_280 // 80 ms, mono signed PCM16 little-endian.
    private const val VERSION = 1

    fun signal(id: String, action: CallAction): String = JSONObject()
        .put("v", VERSION).put("callId", id).put("action", action.name).toString()

    fun parseSignal(payload: String): Pair<String, CallAction> {
        require(payload.length <= 256)
        val json = JSONObject(payload)
        return id(json) to CallAction.valueOf(json.getString("action"))
    }

    fun audio(id: String, sequence: Long, bytes: ByteArray): String {
        require(bytes.size == FRAME_BYTES && sequence >= 0)
        return JSONObject().put("v", VERSION).put("callId", id).put("seq", sequence)
            .put("pcm", Base64.getEncoder().encodeToString(bytes)).toString()
    }

    data class Frame(val id: String, val sequence: Long, val bytes: ByteArray)

    fun parseAudio(payload: String): Frame {
        require(payload.length <= 2_048)
        val json = JSONObject(payload)
        val id = id(json)
        val sequence = json.getLong("seq")
        require(sequence >= 0)
        val bytes = Base64.getDecoder().decode(json.getString("pcm"))
        require(bytes.size == FRAME_BYTES)
        return Frame(id, sequence, bytes)
    }

    private fun id(json: JSONObject): String {
        require(json.getInt("v") == VERSION)
        val id = json.getString("callId")
        require(UUID.fromString(id).toString() == id)
        return id
    }
}
