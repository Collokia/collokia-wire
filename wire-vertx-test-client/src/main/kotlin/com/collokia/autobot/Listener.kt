package com.collokia.autobot

import org.vertx.java.platform.Verticle
import com.collokia.wire.client.vertx.VertxClient
import java.util.concurrent.atomic.AtomicBoolean
import com.fasterxml.jackson.module.kotlin.*
import com.collokia.wire.client.vertx.Message
import com.collokia.util.UTF_8
import kotlin.concurrent.thread
import java.io.BufferedReader
import java.io.InputStreamReader
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream


internal val BROWSER_TOPIC = "browser"
internal val IDE_TOPIC = "ide"

internal val BROWSER_EVENT_GOOGLE_SEARCH = "googleSearch"
internal val BROWSER_EVENT_AFTER_NAVIGATE = "afterNavigate"
internal val BROWSER_EVENT_BEFORE_NAVIGATE = "beforeNavigate"

data class EventHeader(val eventCategory: String, val eventType: String, val source: String)

class AutobotServer() : Verticle() {
    private var browserClient: VertxClient? = null
    private var ideClient: VertxClient? = null
    private val JSON = jacksonObjectMapper()
    private val LOG = LoggerFactory.getLogger(this.javaClass)
    private val recommender = BrowserRecommender()

    override fun start() {
        print("Starting...")
        val browserClient = VertxClient(vertx.eventBus(), BROWSER_TOPIC,
                onMessage = {  msg ->
                    val messageReader = BufferedReader(InputStreamReader(ByteArrayInputStream(msg.body)))
                    val eventHeader: EventHeader = JSON.readValue(messageReader.readLine())
                    LOG.debug("Received MESSAGE from ${msg.userKey} with HDR: $eventHeader")
                    val eventBodyJson = messageReader.readLine()

                    if (eventHeader.eventCategory == BROWSER_TOPIC) {
                        if (eventHeader.eventType == BROWSER_EVENT_GOOGLE_SEARCH) {
                            try {
                                val searchEvent: GoogleQueryEvent = JSON.readValue(eventBodyJson)
                                LOG.debug("GOOGLE EVENT: $searchEvent")
                                recommender.forGoogleSearch(searchEvent) { bodyString ->
                                    msg.createMessage(bodyString, "test-company-2/hlJl_plY9owvQE7gRTYvlg/${BROWSER_TOPIC}").send()

                                }
                            }
                            catch (ex: Throwable) {
                                LOG.error("BAD JSON: ${eventBodyJson}")
                                LOG.error("          ${ex.getMessage()}")
                                // LOG.error(ex.getMessage(), ex)
                            }
                        } else if (eventHeader.eventType == BROWSER_EVENT_BEFORE_NAVIGATE) {
                            val navEvent: BrowserNavigateEvent = JSON.readValue(eventBodyJson)
                            LOG.trace("NAV EVENT (ignored): $navEvent")
                        } else if (eventHeader.eventType == BROWSER_EVENT_AFTER_NAVIGATE) {
                            val navEvent: BrowserNavigateEvent = JSON.readValue(eventBodyJson)
                            LOG.debug("NAV EVENT: $navEvent")
                            recommender.forBrowserNavigate(navEvent) { bodyString ->
                                msg.createMessage(bodyString, "test-company-2/hlJl_plY9owvQE7gRTYvlg/${BROWSER_TOPIC}").send()
                            }
                        }
                        else {
                            LOG.warn("Unhandled event type: $eventHeader w/MESSAGE BODY: $eventBodyJson")
                        }
                    }
                    else {
                        LOG.error("Unknown message category: $eventHeader w/MESSAGE BODY: $eventBodyJson")
                    }
                    // LOG.debug("Message: $it")
                },
                onError = {
                    LOG.error("ERROR: ${it.getMessage()}")
                })

    }

    override fun stop() {
        IOUtils.closeQuietly(ideClient)
        IOUtils.closeQuietly(browserClient)
    }
}

