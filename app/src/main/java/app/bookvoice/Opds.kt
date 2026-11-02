package app.bookvoice

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI

data class OpdsLink(val href: String, val type: String, val rel: String)

data class OpdsEntry(val title: String, val author: String, val links: List<OpdsLink>) {
    val nav get() = links.firstOrNull { it.type.contains("atom+xml") }
    val epub get() = links.firstOrNull { it.type.contains("epub") }
}

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>, val next: String?)

val http = OkHttpClient()

/** Вызывать не с главного потока. */
fun fetchFeed(url: String): OpdsFeed =
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code}" }
        parseFeed(r.body!!.byteStream(), url)
    }

fun parseFeed(stream: InputStream, base: String): OpdsFeed {
    val p = Xml.newPullParser()
    p.setInput(stream, null)
    var feedTitle = ""
    var next: String? = null
    val entries = mutableListOf<OpdsEntry>()
    var inEntry = false
    var inAuthor = false
    var title = ""
    var author = ""
    var links = mutableListOf<OpdsLink>()
    fun abs(h: String) = URI(base).resolve(h).toString()

    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "entry" -> { inEntry = true; title = ""; author = ""; links = mutableListOf() }
                "author" -> inAuthor = true
                "title" -> { val t = p.nextText(); if (inEntry) title = t else feedTitle = t }
                "name" -> if (inAuthor) author = p.nextText()
                "link" -> {
                    val href = p.getAttributeValue(null, "href") ?: ""
                    val type = p.getAttributeValue(null, "type") ?: ""
                    val rel = p.getAttributeValue(null, "rel") ?: ""
                    if (inEntry) links.add(OpdsLink(abs(href), type, rel))
                    else if (rel == "next") next = abs(href)
                }
            }
            XmlPullParser.END_TAG -> when (p.name) {
                "entry" -> { entries.add(OpdsEntry(title.trim(), author, links)); inEntry = false }
                "author" -> inAuthor = false
            }
        }
        ev = p.next()
    }
    return OpdsFeed(feedTitle, entries, next)
}
