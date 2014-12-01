package com.collokia.wire.server

import org.vertx.java.platform.Verticle
import org.vertx.java.core.http.ServerWebSocket
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.Handler
import org.vertx.java.core.Vertx
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import org.vertx.java.core.buffer.Buffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.collokia.wire.protocol.ConnectionMetadata
import java.io.ByteArrayOutputStream
import com.collokia.wire.protocol.MessageEnvelope
import com.collokia.util.serialization.readBoolean
import com.collokia.util.serialization.readUtf8
import com.amazonaws.services.s3.AmazonS3Client
import com.collokia.util.aws.keyExists
import com.collokia.wire.protocol.MessageAction
import com.collokia.util.serialization.writeBoolean
import com.collokia.util.serialization.writeUtf8
import com.collokia.config.AppConfig

class MessageServer() : Verticle() {

    class object {

        val LOG = LoggerFactory.getLogger(javaClass<MessageServer>())

        private class SendBytesHandler(val webSocket: ServerWebSocket, val key: String) : Handler<Message<ByteArray>> {

            override fun handle(message: Message<ByteArray>?) {
                val bytes = message?.body()
                if (bytes != null) {
                    val cursor = AtomicInteger(0)
                    val envelope = MessageEnvelope.fromBytes(bytes, cursor)
                    if (envelope.isResponse) {
                        val isTrusted = readBoolean(bytes, cursor)
                        if (!isTrusted) {
                            val key = readUtf8(bytes, cursor)
                            if (key == key) {
                                webSocket.write(Buffer(envelope.toBytes()))
                            }
                        } else {
                            webSocket.write(Buffer(envelope.toBytes()))
                        }
                    } else {
                        webSocket.write(Buffer(bytes))
                    }
                }
            }
        }

        private class WebSocketsHandler(val vertx: Vertx, val config: MessageServerConfig, val s3Client: AmazonS3Client) : Handler<ServerWebSocket> {

            override fun handle(webSocket: ServerWebSocket) {
                if (webSocket.path().startsWith("/ws/")) {
                    val lastSlash = webSocket.path().lastIndexOf('/')
                    val key = if (lastSlash == 3) "" else webSocket.path().substring(4, lastSlash)
                    val receiveKey = UUID.randomUUID()
                    if (key != config.healthCheckKey && s3Client.keyExists(config.s3UploadBucket, key)) {
                        val category = webSocket.path().substring(lastSlash + 1)
                        val responseTail = buildResponseTail(key, category)
                        val prefix = "${key}/"
                        val allSub = "${prefix}all"
                        val subscription = if (key.isEmpty()) category else "${prefix}${category}"
                        val bytesHandler = SendBytesHandler(webSocket, key)
                        val lastDataTime = AtomicLong(System.currentTimeMillis())
                        webSocket.dataHandler {
                            lastDataTime.set(System.currentTimeMillis())
                            if (it != null) {
                                val bytes = it.getBytes()
                                if (bytes != null) {
                                    val envelope = MessageEnvelope.fromBytes(bytes)
                                    when (envelope.action) {
                                        MessageAction.INIT -> {
                                            webSocket.write(Buffer(MessageEnvelope(envelope.id, MessageAction.META, body = ConnectionMetadata(subscription, receiveKey, false).toBytes()).toBytes()))
                                        }
                                        MessageAction.PING -> {
                                            webSocket.write(Buffer(MessageEnvelope(envelope.id, MessageAction.PONG).toBytes()))
                                        }
                                        MessageAction.SEND -> {
                                            if (!envelope.needsResponse || envelope.returnAddress == receiveKey) {
                                                webSocket.write(Buffer(MessageEnvelope(envelope.id, MessageAction.SENT).toBytes()))
                                                if (envelope.isResponse) {
                                                    val buff = Buffer(envelope.toBytes())
                                                    buff.appendBytes(responseTail)
                                                    vertx.eventBus().send(envelope.address.toString(), buff.getBytes())
                                                } else if (envelope.isManualAddress) {
                                                    vertx.eventBus().publish("${prefix}${envelope.manualAddress}", bytes)
                                                } else {
                                                    vertx.eventBus().send(category, bytes)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val timeoutChecker = vertx.setPeriodic(config.pingInterval, {
                            if (lastDataTime.get() < System.currentTimeMillis() - config.timeout) {
                                webSocket.close()
                                LOG.info("Socket timed out: ${subscription} : ${receiveKey}")
                            }
                        })
                        vertx.eventBus().registerHandler(allSub, bytesHandler)
                        vertx.eventBus().registerHandler(subscription, bytesHandler)
                        vertx.eventBus().registerHandler(receiveKey.toString(), bytesHandler)
                        webSocket.closeHandler {
                            vertx.eventBus().unregisterHandler(allSub, bytesHandler)
                            vertx.eventBus().unregisterHandler(subscription, bytesHandler)
                            vertx.eventBus().unregisterHandler(receiveKey.toString(), bytesHandler)
                            vertx.cancelTimer(timeoutChecker)
                            LOG.info("Closed web socket: ${subscription} : ${receiveKey}")
                        }
                        LOG.info("Opened web socket: ${subscription} : ${receiveKey}")
                    } else {
                        webSocket.reject()
                    }
                } else {
                    webSocket.reject()
                }
            }
        }

        private fun WebSocketsHandler.buildResponseTail(key: String, category: String): ByteArray {
            val baos = ByteArrayOutputStream()
            writeBoolean(false, baos)
            writeUtf8(key, baos)
            writeUtf8(category, baos)
            return baos.toByteArray()
        }
    }

    override fun start() {
        val appConfig = AppConfig.instance
        val s3Client = AmazonS3Client(appConfig.awsCredentials)
        val config = MessageServerConfig.fromConfig(appConfig.config)
        vertx.createHttpServer().websocketHandler(WebSocketsHandler(vertx, config, s3Client)).listen(config.port, config.host)
        LOG.info("${this.javaClass.getSimpleName()} started listening on ${config.host}:${config.port}")
    }
}