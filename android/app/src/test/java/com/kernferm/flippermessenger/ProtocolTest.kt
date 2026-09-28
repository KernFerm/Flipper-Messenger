package com.kernferm.flippermessenger

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val session = ByteArray(12) { (it + 11).toByte() }

    @Test fun encryptedRoundTripPreservesFields() {
        val original = Protocol.Message(Protocol.SEND_SMS, 0x102030405060708L, 9, session,
            "payload across the real frame codec".toByteArray(), true)
        val decoded = Protocol.decode(Protocol.encode(original, key), key, 8)
        assertEquals(original.type, decoded.type)
        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.sequence, decoded.sequence)
        assertArrayEquals(original.session, decoded.session)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun replayIsRejected() {
        val message = Protocol.Message(Protocol.PING, 4, 2, session, encrypted = true)
        Protocol.decode(Protocol.encode(message, key), key, 2)
    }

    @Test(expected = Exception::class)
    fun modifiedCiphertextIsRejected() {
        val message = Protocol.Message(Protocol.PING, 4, 2, session, byteArrayOf(1, 2, 3), true)
        val frame = Protocol.encode(message, key)
        frame[Protocol.HEADER] = (frame[Protocol.HEADER].toInt() xor 1).toByte()
        Protocol.decode(frame, key, 0)
    }

    @Test fun clearHelloHasExactHeaderAndPayload() {
        val frame = Protocol.encode(Protocol.Message(Protocol.HELLO, payload = byteArrayOf(1, 2)), null)
        assertEquals(Protocol.HEADER + 2, frame.size)
        assertEquals(Protocol.HELLO, Protocol.decode(frame, null, 0).type)
    }
}

