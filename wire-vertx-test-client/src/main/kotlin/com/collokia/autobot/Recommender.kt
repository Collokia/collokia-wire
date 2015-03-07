package com.collokia.autobot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import com.fasterxml.jackson.annotation.JsonProperty

internal val BROWSER_DATA_MESSAGE_TYPE = "BroswerPluginNotes"
internal val BROWSER_NOTICE_TYPE = "Warning"
internal val BROWSER_NOTICE_KEEPALIVE_MINUTES = 5

data class BrowserDataMessage(val messageType: String = BROWSER_DATA_MESSAGE_TYPE, val data: BrowserNotifications)
data class BrowserNotifications(val expiresMinutes: Int, val googleSearchs: List<GoogleSearchNotice>, val pages: List<WebPageNotice>)
data class GoogleSearchNotice(JsonProperty("UUID") val UUID: String, val query: String, val topWidget: Boolean, val rightWidget: Boolean) // , val urlNotices: Boolean = true)
data class WebPageNotice(JsonProperty("UUID") val UUID: String, val type: String, val domain: String, val host: String = "*", val path: String, val prefixMatchPath: Boolean = true, val params: Set<UrlParam>, val ignoreParams: Set<String>)
data class UrlParam(val name: String, val value: String)

internal class BrowserRecommender {
    private val JSON = jacksonObjectMapper()
    private val LOG = LoggerFactory.getLogger(this.javaClass)

    // mimic behavior of recommender (using cheesy hard coded ideas) that Machine Learning will replace to test ideas of what signals might be useful

    public fun forGoogleSearch(event: GoogleQueryEvent, reply: (msg: ByteArray) -> Unit) {
        if (event.normalizedQuery.contains("json")) {
            val googleSearchNotices = listOf(
                    GoogleSearchNotice("GOOGLE-QUERY-${event.normalizedQuery.replace(' ','-')}", event.cleanQuery, true, true)
            )
            val webPageNotices = listOf(
                    WebPageNotice("WIKIPEDIA-0001", BROWSER_NOTICE_TYPE, "wikipedia.org", "*", "*", true, setOf(), setOf()),
                    WebPageNotice("STACKOVERFLOW-2591098", BROWSER_NOTICE_TYPE, "stackoverflow.com", "*", "questions/2591098", true, setOf(), setOf()),
                    WebPageNotice("STACKOVERFLOW-4935632", BROWSER_NOTICE_TYPE, "stackoverflow.com", "*", "questions/4935632", true, setOf(), setOf()),
                    WebPageNotice("GITHUB-0001", BROWSER_NOTICE_TYPE, "github.com", "*", "*", true, setOf(), setOf()),
                    WebPageNotice("JSON-ORG-0001", BROWSER_NOTICE_TYPE, "json.org", "*", "*", true, setOf(), setOf())
            )
            val recommendations = BrowserDataMessage(BROWSER_DATA_MESSAGE_TYPE, BrowserNotifications(BROWSER_NOTICE_KEEPALIVE_MINUTES, googleSearchNotices, webPageNotices))

            reply(JSON.writeValueAsBytes(recommendations))
        }
    }

    public fun forBrowserNavigate(event: BrowserNavigateEvent, reply: (msg: ByteArray) -> Unit) {

    }

}