package com.kernferm.flippermessenger

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Protocol {
    const val MAGIC = 0x47534D46
    const val VERSION: Byte = 1
    const val HEADER = 40
    const val TAG = 16
    const val MAX_PAYLOAD = 400
    const val ENCRYPTED = 1

    const val HELLO = 1
    const val HELLO_REPLY = 2
    const val PROVISION_KEY = 4
    const val AUTH_RESPONSE = 6
    const val PING = 7
    const val PONG = 8
    const val CONTACT_BEGIN = 16
    const val CONTACT_ENTRY = 17
    const val CONTACT_END = 18
    const val CONTACT_RESULT = 19
    const val SEND_SMS = 32
    const val ACCEPTED = 33
    const val SMS_SENT = 34
    const val SMS_DELIVERED = 35
    const val SMS_FAILED = 36
    const val INCOMING_SMS = 48
    const val ERROR = 127

    data class Message(
        val type: Int,
        val requestId: Long = 0,
        val sequence: Long = 0,
        val session: ByteArray = ByteArray(12),
        val payload: ByteArray = byteArrayOf(),
        val encrypted: Boolean = false,
    )

    fun encode(message: Message, key: ByteArray?): ByteArray {
        require(message.payload.size <= MAX_PAYLOAD)
        require(message.session.size >= 12)
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(MAGIC).put(VERSION).put(message.type.toByte())
            .put(if (message.encrypted) ENCRYPTED.toByte() else 0).put(0)
            .putShort(message.payload.size.toShort()).putShort(0)
            .putLong(message.requestId).putLong(message.sequence).put(message.session, 0, 12)
        if (!message.encrypted) return header.array() + message.payload
        require(key?.size == 32 && message.sequence > 0)
        val nonce = message.session.copyOfRange(0, 4) +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(message.sequence).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header.array())
        return header.array() + cipher.doFinal(message.payload)
    }

    fun decode(frame: ByteArray, key: ByteArray?, minimumSequence: Long): Message {
        require(frame.size >= HEADER) { "short frame" }
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC && b.get() == VERSION) { "bad protocol" }
        val type = b.get().toInt() and 0xff
        val flags = b.get().toInt() and 0xff
        b.get()
        val length = b.short.toInt() and 0xffff
        b.short
        require(length <= MAX_PAYLOAD)
        val request = b.long
        val sequence = b.long
        val session = ByteArray(12).also(b::get)
        val encrypted = flags and ENCRYPTED != 0
        require(frame.size == HEADER + length + if (encrypted) TAG else 0) { "bad length" }
        val content = frame.copyOfRange(HEADER, frame.size)
        val payload = if (!encrypted) content else {
            require(key?.size == 32 && sequence > minimumSequence) { "unauthorized/replayed" }
            val nonce = session.copyOfRange(0, 4) +
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(frame.copyOfRange(0, HEADER))
            cipher.doFinal(content)
        }
        return Message(type, request, sequence, session, payload, encrypted)
    }
}

