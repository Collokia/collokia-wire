package com.collokia.autobot

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.util.Version
import kotlin.properties.Delegates
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.slf4j.LoggerFactory
import org.apache.lucene.search.FuzzyQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.PrefixQuery

public data class GoogleQueryEvent(val origin: String, val query: String, val url: String, val hash: String,
                                   val results: List<GoogleResult>,
                                   JsonProperty("related_query") val relatedQuery: String = "",
                                   JsonProperty("related_searches") val relatedSearches: List<String> = listOf()) {
    val cleanQuery: String by Delegates.lazy {
        if (relatedQuery.isNotEmpty()) {
            relatedQuery
        } else {
            if (query.contains("%20") || query.contains("+")) {
                URLDecoder.decode(query, "UTF-8")
            } else {
                query
            }
        }
    }
    val normalizedQuery: String by Delegates.lazy {
        parseGoogleQuery(cleanQuery)
    }
}

public data class GoogleResult(val title: String, val url: String)
public data class BrowserNavigateEvent(val url: String)


private fun parseGoogleQuery(qry: String): String {
    try {
        return SimulatedGoogleQueryParser().normalizeQuery(qry)
    } catch (ex: Exception) {
        println(ex.getMessage())
        val results = qry.split(' ').toSet().toSortedList().joinToString("|")
        println("Google query parsing failed, defaulted to ${results}")
        return results
    }
}

private val FAKEFIELD = "FAKEFIELD"

public class SimulatedGoogleQueryParser() : org.apache.lucene.queryparser.simple.SimpleQueryParser(StandardAnalyzer(Version.LATEST), mapOf("FAKEFIELD" to 1.0F),
        org.apache.lucene.queryparser.simple.SimpleQueryParser.AND_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.OR_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.NOT_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.PHRASE_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.WHITESPACE_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.PREFIX_OPERATOR or
                org.apache.lucene.queryparser.simple.SimpleQueryParser.FUZZY_OPERATOR) {

    private val LOG = LoggerFactory.getLogger(this.javaClass)
    private val collectedTerms = sortedSetOf<String>()

    // TODO: we are missing the negation on -Something
    //       to solve this we have to use the query boolean tree and not just collect text terms
    //       because we don't know it happens when they make a BooleanClause.Occur.MUST_NOT to wrap
    //       the query.   Is it easier to do that, or easier to just parse ourselves.

    override public fun parse(queryText: String): Query? {
        collectedTerms.clear()
        return super.parse(queryText)
    }

    public fun normalizeQuery(queryText: String): String {
        val parsed = parse(queryText)
        val results = collectedTerms.joinToString("|")
        LOG.debug("Google Query Normalized: ${results}")
        return results
    }

    override protected fun newDefaultQuery(textTemp: String): Query? {
        val text = textTemp.toLowerCase()
        val bq = BooleanQuery(true)
        val q = createBooleanQuery(FAKEFIELD, text, super.getDefaultOperator())
        if (q != null) {
            collectedTerms.add(text)
            LOG.trace("newDefaultQuery($text, $q)}")
            bq.add(q, BooleanClause.Occur.SHOULD)
        }
        else {
            LOG.trace("ignore($text)")
        }
        return super.simplify(bq)
    }

    override protected fun newFuzzyQuery(textTemp: String, fuzziness: Int): Query? {
        return newDefaultQuery(textTemp)
    }

    override protected fun newPhraseQuery(textTemp: String, slop: Int): Query? {
        val text = textTemp.toLowerCase()
        val bq = BooleanQuery(true)
        val q = createPhraseQuery(FAKEFIELD, text, slop)
        if (q != null) {
            collectedTerms.add(text)
            LOG.trace("newPhraseQuery($text, $q)}")
            bq.add(q, BooleanClause.Occur.SHOULD)
        }
        else {
            LOG.trace("ignore($text)")
        }
        return super.simplify(bq)
    }

    override protected fun newPrefixQuery(textTemp: String): Query? {
        return newDefaultQuery(textTemp)
    }
}
