package com.collokia.wire.protocol

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.util.UUID
import com.collokia.util.serialization.writeByte
import com.collokia.util.serialization.readByte
import com.collokia.util.serialization.writeUuid
import com.collokia.util.serialization.readUuid
import com.collokia.util.serialization.writeBytes
import com.collokia.util.serialization.readBytes
import com.collokia.util.serialization.writeUtf8
import com.collokia.util.serialization.readUtf8
import com.collokia.util.serialization.readBoolean
import com.collokia.util.serialization.writeBoolean

val UUID_ZERO = UUID(0, 0)
val BYTES_ZERO = ByteArray(0)

public class MessageAction() {

    companion object {
        val INIT = 0
        val META = 1
        val PING = 2
        val PONG = 3
        val SEND = 4
        val SENT = 5
    }
}

public class MessageEnvelope(val id: UUID,
                             val action: Int,
                             val isManualAddress: Boolean = false,
                             val isResponse: Boolean = false,
                             val needsResponse: Boolean = false,
                             val manualAddress: String = "",
                             val address: UUID = UUID_ZERO,
                             val respondId: UUID = UUID_ZERO,
                             val returnAddress: UUID = UUID_ZERO,
                             val body: ByteArray = BYTES_ZERO) {

    companion object {

        fun fromBytes(bytes: ByteArray, cursor: AtomicInteger): MessageEnvelope {
            val id = readUuid(bytes, cursor)
            val action = readByte(bytes, cursor)
            when (action) {
                MessageAction.INIT -> {
                    return MessageEnvelope(id, action, needsResponse = true)
                }
                MessageAction.META -> {
                    val body = readBytes(bytes, cursor)
                    return MessageEnvelope(id, action, isResponse = true, respondId = id, body = body)
                }
                MessageAction.PING -> {
                    return MessageEnvelope(id, action, needsResponse = true)
                }
                MessageAction.PONG -> {
                    return MessageEnvelope(id, action, isResponse = true, respondId = id)
                }
                MessageAction.SEND -> {
                    val booleans = readByte(bytes, cursor)
                    val isManualAddress = (4 and booleans) != 0
                    val isResponse = (2 and booleans) != 0
                    val needsResponse = (1 and booleans) != 0
                    val manualAddress = if (isManualAddress) readUtf8(bytes, cursor) else ""
                    val address = if (isResponse) readUuid(bytes, cursor) else UUID_ZERO
                    val respondId = if (isResponse) readUuid(bytes, cursor) else UUID_ZERO
                    val returnAddress = if (needsResponse) readUuid(bytes, cursor) else UUID_ZERO
                    val body = readBytes(bytes, cursor)
                    return MessageEnvelope(id, action, isManualAddress, isResponse, needsResponse, manualAddress, address, respondId, returnAddress, body)
                }
                MessageAction.SENT -> {
                    return MessageEnvelope(id, action, isResponse = true, respondId = id)
                }
                else -> {
                    return MessageEnvelope(id, action)
                }
            }
        }

        fun fromBytes(bytes: ByteArray): MessageEnvelope {
            return fromBytes(bytes, AtomicInteger(0))
        }
    }

    fun <S : OutputStream> writeTo(outputStream: S): S {
        writeUuid(id, outputStream)
        writeByte(action, outputStream)
        when (action) {
            MessageAction.SEND -> {
                writeByte((if (isManualAddress) 4 else 0) or (if (isResponse) 2 else 0) or (if (needsResponse) 1 else 0), outputStream)
                if (isManualAddress) {
                    writeUtf8(manualAddress, outputStream)
                }
                if (isResponse) {
                    writeUuid(address, outputStream)
                    writeUuid(respondId, outputStream)
                }
                if (needsResponse) {
                    writeUuid(returnAddress, outputStream)
                }
                writeBytes(body, outputStream)
            }
            MessageAction.META -> {
                writeBytes(body, outputStream)
            }
            else -> {
            }
        }
        return outputStream
    }

    fun toBytes(): ByteArray {
        return writeTo(ByteArrayOutputStream()).toByteArray()
    }
}

public class ConnectionMetadata(val directAddress: String = "", val replyAddress: UUID = UUID_ZERO, val isTrusted: Boolean = false) {

    companion object {

        fun fromBytes(bytes: ByteArray, cursor: AtomicInteger): ConnectionMetadata {
            val directAddress = readUtf8(bytes, cursor)
            val replyAddress = readUuid(bytes, cursor)
            val isTrusted = readBoolean(bytes, cursor)
            return ConnectionMetadata(directAddress, replyAddress, isTrusted)
        }

        fun fromBytes(bytes: ByteArray): ConnectionMetadata {
            return fromBytes(bytes, AtomicInteger(0))
        }
    }

    fun <S : OutputStream> writeTo(outputStream: S): S {
        writeUtf8(directAddress, outputStream)
        writeUuid(replyAddress, outputStream)
        writeBoolean(isTrusted, outputStream)
        return outputStream
    }

    fun toBytes(): ByteArray {
        return writeTo(ByteArrayOutputStream()).toByteArray()
    }
}