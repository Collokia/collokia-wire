package com.collokia.wire.client.vertx

import org.vertx.java.core.eventbus.EventBus
import com.collokia.wire.protocol.MessageEnvelope
import com.collokia.wire.protocol.MessageAction
import org.vertx.java.core.buffer.Buffer
import java.io.ByteArrayOutputStream
import com.collokia.util.serialization.writeBoolean
import com.collokia.util.serialization.writeUtf8
import com.collokia.wire.protocol.ConnectionMetadata
import java.util.UUID
import java.io.Closeable
import org.vertx.java.core.Handler
import java.util.concurrent.atomic.AtomicInteger

public class VertxClient(val eventBus: EventBus,
                         val category: String,
                         val messageThreads: Int = 2,
                         val onMessage: (Message) -> Unit = {},
                         val onError: (Throwable) -> Unit = {}) : Closeable {

    private val onMessageCallable = onMessage
    private val metadata = ConnectionMetadata("", UUID.randomUUID(), true)
    private val responseTail = buildResponseTail("", category)
    private val defaultMessage = Message({ metadata }, { sendMessage(it) }, MessageEnvelope(com.collokia.wire.protocol.UUID_ZERO, MessageAction.SEND))
    private val handlingService = ResponseHandlingService(messageThreads)
    private val busHandler = MessageHandler({(envelope: MessageEnvelope) ->
        val message = Message({ metadata }, { sendMessage(it) }, envelope)
        if (!message.envelope.isResponse || !handlingService.handleResponse(message)) {
            onMessageCallable(message)
        }
    })

    class object {
        val DEFAULT_RESPOND_TIMEOUT: Long = 10000
        val NO_HANDLER = {(message: Message) -> }

        private class MessageHandler(val handle: (MessageEnvelope) -> Unit) : Handler<org.vertx.java.core.eventbus.Message<ByteArray>> {

            override fun handle(message: org.vertx.java.core.eventbus.Message<ByteArray>?) {
                val bytes = message?.body()
                if (bytes != null) {
                    val cursor = AtomicInteger(0)
                    val envelope = MessageEnvelope.fromBytes(bytes, cursor)
                    handle(envelope)
                }
            }
        }
    }

    {
        eventBus.registerHandler(category, busHandler)
        eventBus.registerHandler(metadata.replyAddress.toString(), busHandler)
    }

    override public fun close() {
        eventBus.unregisterHandler(category, busHandler)
        eventBus.unregisterHandler(metadata.replyAddress.toString(), busHandler)
    }

    fun createMessage(body: ByteArray,
                      address: String = "",
                      respondTimeout: Long = VertxClient.DEFAULT_RESPOND_TIMEOUT,
                      onResponse: ((Message) -> Unit) = VertxClient.NO_HANDLER,
                      onRespondFail: ((Message) -> Unit) = VertxClient.NO_HANDLER): Message {
        return defaultMessage.createMessage(body,
                address = address,
                respondTimeout = respondTimeout,
                onResponse = onResponse,
                onRespondFail = onRespondFail)
    }

    private fun sendMessage(message: Message) {
        handlingService.putHandler(message)
        val envelope = message.envelope
        val bytes = envelope.toBytes()
        if (envelope.isResponse) {
            val buff = Buffer(bytes)
            buff.appendBytes(responseTail)
            eventBus.send(envelope.address.toString(), buff.getBytes())
        } else if (envelope.isManualAddress) {
            eventBus.publish(envelope.manualAddress, bytes)
        }
    }

    private fun buildResponseTail(key: String, category: String): ByteArray {
        val baos = ByteArrayOutputStream()
        writeBoolean(false, baos)
        writeUtf8(key, baos)
        writeUtf8(category, baos)
        return baos.toByteArray()
    }
}