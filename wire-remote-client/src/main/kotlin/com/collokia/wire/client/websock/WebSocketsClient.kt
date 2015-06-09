package com.collokia.wire.client.websock

import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.websocket.WebSocketUpgradeHandler
import com.ning.http.client.websocket.WebSocket
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong
import com.ning.http.client.websocket.WebSocketByteListener
import com.collokia.wire.protocol.MessageEnvelope
import java.util.UUID
import org.apache.commons.io.IOUtils
import java.io.Closeable
import com.collokia.wire.protocol.MessageAction
import com.collokia.wire.protocol.ConnectionMetadata
import java.util.concurrent.atomic.AtomicBoolean
import com.collokia.wire.protocol.UUID_ZERO

public class WebSocketsClient(val endpoint: String,
                              val messageThreads: Int = 2,
                              val pingInterval: Long = 10000,
                              val idleTimeout: Long = 30000,
                              val onMessage: (message: Message) -> Unit = {},
                              val onOpen: (metadata: ConnectionMetadata) -> Unit = {},
                              val onClose: (client: WebSocketsClient) -> Unit = {},
                              val onError: (error: Throwable) -> Unit = {}) : Closeable {

    private val onMessageCallable = onMessage
    private val onOpenCallable = onOpen
    private val onCloseCallable = onClose
    private val onErrorCallable = onError
    private val httpClient = AsyncHttpClient()
    private val closed = AtomicBoolean(false)
    private val lastMessageTime = AtomicLong(System.currentTimeMillis())
    private val lastOpenTime = AtomicLong(System.currentTimeMillis())
    private var webSocket: WebSocket? = null
    private val client = this
    private val pingTimer = Timer(true)
    private val pingTask = startPingTask()
    private var metadata = ConnectionMetadata()
    private val defaultMessage = Message({ metadata }, { sendMessage(it) }, MessageEnvelope(UUID_ZERO, MessageAction.SEND))
    private val handlingService = MessageHandlingService(messageThreads)

    companion object {
        val DEFAULT_SEND_TIMEOUT: Long = 5000
        val DEFAULT_RESPOND_TIMEOUT: Long = 10000
        val NO_HANDLER = { message: Message -> }
    }

    fun createMessage(body: ByteArray,
                      address: String = "",
                      sendTimeout: Long = WebSocketsClient.DEFAULT_SEND_TIMEOUT,
                      respondTimeout: Long = WebSocketsClient.DEFAULT_RESPOND_TIMEOUT,
                      onSendFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                      onResponse: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                      onRespondFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER): Message {
        return defaultMessage.createMessage(body,
                address = address,
                sendTimeout = sendTimeout,
                respondTimeout = respondTimeout,
                onSendFail = onSendFail,
                onResponse = onResponse,
                onRespondFail = onRespondFail)
    }

    private fun createCustomMessage(envelope: MessageEnvelope,
                                    sendTimeout: Long = WebSocketsClient.DEFAULT_SEND_TIMEOUT,
                                    respondTimeout: Long = WebSocketsClient.DEFAULT_RESPOND_TIMEOUT,
                                    onSendFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                                    onResponse: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER,
                                    onRespondFail: ((Message) -> Unit) = WebSocketsClient.NO_HANDLER): Message {
        return Message({ metadata }, { sendMessage(it) }, envelope,
                sendTimeout = sendTimeout,
                respondTimeout = respondTimeout,
                onSendFail = onSendFail,
                onResponse = onResponse,
                onRespondFail = onRespondFail)
    }

    private fun sendInitMessage() {
        createCustomMessage(MessageEnvelope(UUID.randomUUID(), MessageAction.INIT,
                needsResponse = true),
                onSendFail = {
                    it.send()
                },
                onRespondFail = {
                    it.send()
                },
                onResponse = {
                    metadata = ConnectionMetadata.fromBytes(it.body)
                    onOpenCallable(metadata)
                }).send()
    }

    private fun sendPingMessage() {
        createCustomMessage(MessageEnvelope(UUID.randomUUID(), MessageAction.PING, needsResponse = true)).send()
    }

    private fun sendPongMessage(id: UUID) {
        sendBytes(MessageEnvelope(id, MessageAction.PONG).toBytes())
    }

    private fun sendSentMessage(id: java.util.UUID) {
        sendBytes(MessageEnvelope(id, MessageAction.SENT).toBytes())
    }

    private fun sendMessage(message: Message) {
        handlingService.putHandler(message)
        sendBytes(message.envelope.toBytes())
    }

    private fun sendBytes(bytes: ByteArray) {
        webSocket?.sendMessage(bytes)
    }

    override public fun close() {
        closed.set(true)
        IOUtils.closeQuietly(webSocket)
        IOUtils.closeQuietly(httpClient)
    }

    private fun start() {
        webSocket = httpClient.prepareGet(endpoint).execute(WebSocketUpgradeHandler.Builder().addWebSocketListener(object : WebSocketByteListener {
            override fun onFragment(fragment: ByteArray?, last: Boolean) {
                // nop?
            }

            override fun onMessage(messageBytes: ByteArray?) {
                lastMessageTime.set(System.currentTimeMillis())
                if (messageBytes != null) {
                    val envelope = MessageEnvelope.fromBytes(messageBytes)
                    when (envelope.action) {
                        MessageAction.META -> {
                            handlingService.handleResponse(Message({ metadata }, { sendMessage(it) }, envelope))
                        }
                        MessageAction.PING -> {
                            sendPongMessage(envelope.id)
                        }
                        MessageAction.PONG -> {
                            handlingService.handleResponse(Message({ metadata }, { sendMessage(it) }, envelope))
                        }
                        MessageAction.SEND -> {
                            sendSentMessage(envelope.id)
                            val message = Message({ metadata }, { sendMessage(it) }, envelope)
                            if (!message.envelope.isResponse || !handlingService.handleResponse(message)) {
                                onMessageCallable(message)
                            }
                        }
                        MessageAction.SENT -> {
                            handlingService.markSent(envelope.id)
                        }
                    }
                }
            }

            override fun onOpen(ws: WebSocket?) {
                val now = System.currentTimeMillis()
                lastMessageTime.set(now)
                lastOpenTime.set(now)
                if (ws != null) {
                    webSocket = ws
                    sendInitMessage()
                }
            }

            override fun onClose(ws: WebSocket?) {
                onCloseCallable(client)
            }

            override fun onError(t: Throwable?) {
                if (t != null) {
                    onErrorCallable(t)
                }
            }
        }).build()).get()!!
    }

    private fun startPingTask(): TimerTask {
        val pingTask = object : TimerTask() {
            override fun run() {
                try {
                    if (closed.get()) {
                        cancel()
                        return
                    }
                    val lastTime = lastMessageTime.get()
                    val now = System.currentTimeMillis()
                    if (lastTime < now - pingInterval) {
                        sendPingMessage()
                    }
                    val timedOut = lastTime < now - idleTimeout
                    val localSock = webSocket
                    if (localSock == null || timedOut || !localSock.isOpen()) {
                        IOUtils.closeQuietly(localSock)
                        if (lastOpenTime.get() < now - 5000) {
                            lastOpenTime.set(now)
                            start()
                        }
                    }
                } catch (ignore: Exception) {
                }
            }
        }
        pingTimer.schedule(pingTask, 0, 1000)
        return pingTask
    }
}
