package com.collokia.wire.client.websock

import com.collokia.wire.protocol.MessageEnvelope
import java.util.UUID
import com.collokia.wire.protocol.MessageAction
import com.collokia.wire.protocol.UUID_ZERO
import com.collokia.wire.protocol.ConnectionMetadata

public class Message(private val metadata: () -> ConnectionMetadata,
                     private val doSend: (Message) -> Unit,
                     val envelope: MessageEnvelope,
                     val sendTimeout: Long = WebSocketsClient.DEFAULT_SEND_TIMEOUT,
                     val respondTimeout: Long = WebSocketsClient.DEFAULT_RESPOND_TIMEOUT,
                     val onSendFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                     val onResponse: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                     val onRespondFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER) {

    val id = envelope.id
    val body = envelope.body
    val needsResponse = envelope.needsResponse

    fun createMessage(body: ByteArray,
                      address: String = "",
                      sendTimeout: Long = WebSocketsClient.DEFAULT_SEND_TIMEOUT,
                      respondTimeout: Long = WebSocketsClient.DEFAULT_RESPOND_TIMEOUT,
                      onSendFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                      onResponse: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                      onRespondFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER): Message {
        val isManualAddress = address.isNotEmpty()
        val needsResponse = !onResponse.identityEquals(WebSocketsClient.NO_HANDLER)
        return Message(metadata, doSend,
                MessageEnvelope(UUID.randomUUID(), MessageAction.SEND,
                        isManualAddress = isManualAddress,
                        needsResponse = needsResponse,
                        manualAddress = address,
                        returnAddress = if (needsResponse) metadata().replyAddress else UUID_ZERO,
                        body = body),
                sendTimeout, respondTimeout, onSendFail, onResponse, onRespondFail)
    }

    fun createResponse(body: ByteArray,
                       sendTimeout: Long = WebSocketsClient.DEFAULT_SEND_TIMEOUT,
                       respondTimeout: Long = WebSocketsClient.DEFAULT_RESPOND_TIMEOUT,
                       onSendFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                       onResponse: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                       onRespondFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER): Message {
        val needsResponse = !onResponse.identityEquals(WebSocketsClient.NO_HANDLER)
        return Message(metadata, doSend,
                MessageEnvelope(UUID.randomUUID(), MessageAction.SEND,
                        isResponse = true,
                        needsResponse = needsResponse,
                        address = envelope.returnAddress,
                        respondId = envelope.id,
                        returnAddress = if (needsResponse) metadata().replyAddress else UUID_ZERO,
                        body = body),
                sendTimeout, respondTimeout, onSendFail, onResponse, onRespondFail)
    }

    fun send() {
        doSend(this)
    }
}
