package com.collokia.autobot

import org.vertx.java.platform.Verticle
import com.collokia.wire.client.vertx.VertxClient
import java.util.concurrent.atomic.AtomicBoolean
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.collokia.wire.client.vertx.Message
import com.collokia.util.UTF_8
import kotlin.concurrent.thread
import java.io.BufferedReader
import java.io.InputStreamReader
import org.apache.commons.io.IOUtils


internal val BROWSER_TOPIC = "browser"
internal val IDE_TOPIC = "ide"

data class EventHeader(val eventCategory: String, val eventType: String, val eventSource: String)


class AutobotServer() : Verticle() {
    private var browserClient: VertxClient? = null
    private var ideClient: VertxClient? = null
    private val JSON = jacksonObjectMapper()

    override fun start() {
        print("Starting...")
        val browserClient = VertxClient(vertx.eventBus(), BROWSER_TOPIC,
                onMessage = {
                    print("\nMESSAGE:\n${String(it.body, UTF_8)}")
                   // val event: EventHeader = JSON.readValue()
                },
                onError = {
                    print("\nERROR:\n${it.getMessage()}\n")
                })

    }

    override fun stop() {
        IOUtils.closeQuietly(ideClient)
        IOUtils.closeQuietly(browserClient)
    }
}