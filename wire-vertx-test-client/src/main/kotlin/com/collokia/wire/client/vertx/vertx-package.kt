package com.collokia.wire.client.vertx

import org.vertx.java.platform.Verticle
import org.apache.commons.io.IOUtils
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.atomic.AtomicBoolean
import com.collokia.util.UTF_8
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class TestServer() : Verticle() {

    private var client: VertxClient? = null
    private val stop = AtomicBoolean(false)

    override fun start() {
        print("Starting...")
        val NL = System.lineSeparator()
        val objectMapper = jacksonObjectMapper()
        var lastMessage: Message? = null
        val waitingForInput = AtomicBoolean(false)
        val client = VertxClient(vertx.eventBus(), System.getProperty("collokia.msg.category"),
                onMessage = {
                    val msg = "${NL}received message from: ${it.userKey}${NL}message > $NL${String(it.body, UTF_8)}$NL "
                    if (it.needsResponse) {
                        lastMessage = it
                        print("$msg wants response$NL?$ ")
                    } else {
                        print("$msg $NL$ ")
                    }
                },
                onError = {
                    print("$NL  ${it.getMessage()}$NL")
                })
        this.client = client
        thread(true, true, block = {
            var console = BufferedReader(InputStreamReader(System.`in`))
            var line: String? = ""
            while (!stop.get() && line != null) {
                print("$ ")
                waitingForInput.set(true)
                line = console.readLine()
                waitingForInput.set(false)
                if (line != null && line.isNotEmpty()) {
                    val lineParts = line.split("\\s+".toRegex(), 2)
                    val cmd = lineParts[0]
                    val params = if (lineParts.size() > 1) lineParts[1].trim() else "{}"
                    val map = objectMapper.readValue(params, javaClass<Map<String, Any>>())
                    val address = map.getOrElse("address", { "" }) as String
                    val needsResponse = map.getOrElse("needsResponse", { false }) as Boolean
                    val respondTimeout = (map.getOrElse("timeout", { VertxClient.DEFAULT_RESPOND_TIMEOUT }) as Number).toLong()
                    val body: ByteArray = (map.getOrElse("body", { "" }) as String).toByteArray(UTF_8)
                    when (cmd) {
                        "send" -> {
                            if (needsResponse) {
                                client.createMessage(body, address, respondTimeout = respondTimeout,
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
        })
    }

    override fun stop() {
        stop.set(true)
        IOUtils.closeQuietly(client)
    }
}