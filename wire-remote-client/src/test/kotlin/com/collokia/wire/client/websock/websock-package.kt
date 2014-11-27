package com.collokia.wire.client.websock

import java.io.InputStreamReader
import java.io.BufferedReader
import com.collokia.wire.protocol.MessageEnvelope
import com.collokia.util.UTF_8
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.io.IOUtils
import com.collokia.wire.protocol.MessageAction
import com.collokia.wire.protocol.ConnectionMetadata
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

fun main(args: Array<String>) {
    val NL = System.lineSeparator()
    val objectMapper = jacksonObjectMapper()
    val stop = AtomicBoolean(false)
    var lastMessage: Message? = null
    val openWait = Object()
    val isOpen = AtomicBoolean(false)
    val waitingForInput = AtomicBoolean(false)
    var client = WebSocketsClient(args[0],
            onMessage = {
                if (it.needsResponse) {
                    lastMessage = it
                    print("$NL  message > ${String(it.body, UTF_8)}$NL  wants response$NL$ ")
                } else {
                    print("$NL  message > ${String(it.body, UTF_8)}$NL$ ")
                }
            },
            onOpen = {
                print("$NL  opened connection with directAddress: ${it.directAddress}, replyAddress: ${it.replyAddress}, isTrusted: ${it.isTrusted}$NL")
                if (waitingForInput.get()) {
                    print("$ ")
                }
                isOpen.set(true)
                synchronized(openWait) {
                    openWait.notifyAll()
                }
            },
            onClose = {
                isOpen.set(false)
                print("$NL  closed connection$NL")
            },
            onError = {
                print("$NL  ${it.getMessage()}$NL")
            })
    Runtime.getRuntime().addShutdownHook(Thread({
        stop.set(true)
    }))
    var console = BufferedReader(InputStreamReader(System.`in`))
    var line: String? = ""
    while (!stop.get() && line != null) {
        while (!isOpen.get()) {
            print("$NL  waiting to open connection...$NL")
            synchronized(openWait) {
                openWait.wait()
            }
            while (console.ready()) {
                console.readLine()
            }
        }
        print("$ ")
        waitingForInput.set(true)
        line = console.readLine()
        waitingForInput.set(false)
        if (line != null && line.isNotEmpty()) {
            val lineParts = line!!.split("\\s+", 2)
            val cmd = lineParts[0]
            val params = if (lineParts.size > 1) lineParts[1].trim() else "{}"
            val map = objectMapper.readValue(params, javaClass<Map<String, Any>>())!!
            val address = map.getOrElse("address", { "" }) as String
            val needsResponse = map.getOrElse("needsResponse", { false }) as Boolean
            val respondTimeout = (map.getOrElse("timeout", { WebSocketsClient.DEFAULT_RESPOND_TIMEOUT }) as Number).toLong()
            val body: ByteArray = (map.getOrElse("body", { "" }) as String).getBytes(UTF_8)
            when (cmd) {
                "send" -> {
                    if (needsResponse) {
                        client.createMessage(body, address, respondTimeout = respondTimeout,
                                onSendFail = {
                                    print("$NL  failed to send message: ${String(it.body, UTF_8)}$NL$ ")
                                },
                                onResponse = {
                                    if (it.needsResponse) {
                                        lastMessage = it
                                        print("$NL  response > ${String(it.body, UTF_8)}$NL  wants response$NL$ ")
                                    } else {
                                        print("$NL  response > ${String(it.body, UTF_8)}$NL$ ")
                                    }
                                },
                                onRespondFail = {
                                    print("$NL  did not receive a response to message: ${String(it.body, UTF_8)}$NL$ ")
                                }).send()
                    } else {
                        client.createMessage(body, address).send()
                    }
                    print("  sent > ${String(body, UTF_8)}$NL")
                }
                "reply" -> {
                    val localLast = lastMessage
                    if (localLast == null) {
                        print("  nothing to reply to$NL")
                    } else {
                        if (needsResponse) {
                            localLast.createResponse(body, respondTimeout = respondTimeout,
                                    onSendFail = {
                                        print("$NL  failed to send message: ${String(it.body, UTF_8)}$NL$ ")
                                    },
                                    onResponse = {
                                        if (it.needsResponse) {
                                            lastMessage = it
                                            print("$NL  response > ${String(it.body, UTF_8)}$NL  wants response$NL$ ")
                                        } else {
                                            print("$NL  response > ${String(it.body, UTF_8)}$NL$ ")
                                        }
                                    },
                                    onRespondFail = {
                                        print("$NL  did not receive a response to message: ${String(it.body, UTF_8)}$NL$ ")
                                    }).send()
                        } else {
                            localLast.createResponse(body).send()
                        }
                        print("  replied > ${String(body, UTF_8)}$NL")
                    }
                }
            }
        }
    }
    IOUtils.closeQuietly(client)
}
