package com.collokia.wire.client.vertx

import com.collokia.wire.protocol.MessageAction
import java.util.UUID
import com.collokia.wire.protocol.UUID_ZERO
import com.collokia.wire.protocol.ConnectionMetadata
import com.collokia.wire.protocol.MessageEnvelope

public class Message(private val metadata: () -> ConnectionMetadata,
                     private val doSend: (Message) -> Unit,
                     val envelope: MessageEnvelope,
                     val userKey: String? = null,
                     val respondTimeout: Long = VertxClient.DEFAULT_RESPOND_TIMEOUT,
                     val onResponse: ((Message) -> Unit) = VertxClient.NO_HANDLER,
                     val onRespondFail: ((Message) -> Unit) = VertxClient.NO_HANDLER) {

    val id = envelope.id
    val body = envelope.body
    val needsResponse = envelope.needsResponse

    fun createMessage(body: ByteArray,
                      address: String = "",
                      respondTimeout: Long = VertxClient.DEFAULT_RESPOND_TIMEOUT,
                      onResponse: ((Message) -> Unit) = VertxClient.NO_HANDLER,
                      onRespondFail: ((Message) -> Unit) = VertxClient.NO_HANDLER): Message {
        val isManualAddress = address.isNotEmpty()
        val needsResponse = !onResponse.identityEquals(VertxClient.NO_HANDLER)
        return Message(metadata, doSend,
                MessageEnvelope(UUID.randomUUID(), MessageAction.SEND,
                        isManualAddress = isManualAddress,
                        needsResponse = needsResponse,
                        manualAddress = address,
                        returnAddress = if (needsResponse) metadata().replyAddress else UUID_ZERO,
                        body = body),
                null, respondTimeout, onResponse, onRespondFail)
    }

    fun createResponse(body: ByteArray,
                       respondTimeout: Long = VertxClient.DEFAULT_RESPOND_TIMEOUT,
                       onResponse: ((Message) -> Unit) = VertxClient.NO_HANDLER,
                       onRespondFail: ((Message) -> Unit) = VertxClient.NO_HANDLER): Message {
        val needsResponse = !onResponse.identityEquals(VertxClient.NO_HANDLER)
        return Message(metadata, doSend,
                MessageEnvelope(UUID.randomUUID(), MessageAction.SEND,
                        isResponse = true,
                        needsResponse = needsResponse,
                        address = envelope.returnAddress,
                        respondId = envelope.id,
                        returnAddress = if (needsResponse) metadata().replyAddress else UUID_ZERO,
                        body = body),
                null, respondTimeout, onResponse, onRespondFail)
    }

    fun send() {
        doSend(this)
    }
}
