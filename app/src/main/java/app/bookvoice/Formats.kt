package app.bookvoice

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

const val SUPPORTED = "EPUB, FB2 (и FB2.ZIP), DOCX, ODT, PDF, RTF, HTML, Markdown, TXT"

enum class Fmt { EPUB, FB2, DOCX, ODT, PDF, RTF, HTML, MD, TXT, ZIP_TEXT }

/** Определяет формат по содержимому, а не только по расширению. */
fun sniff(f: File): Fmt {
    val buf = ByteArray(2048)
    val n = f.inputStream().use { it.read(buf) }.coerceAtLeast(0)
    val head = buf.copyOf(n)
    fun starts(s: String) = n >= s.length && String(head, 0, s.length, Charsets.ISO_8859_1) == s
    if (starts("%PDF")) return Fmt.PDF
    if (starts("PK")) return runCatching {
        ZipFile(f).use { z ->
            when {
                z.getEntry("META-INF/container.xml") != null -> Fmt.EPUB
                z.getEntry("word/document.xml") != null -> Fmt.DOCX
                z.getEntry("content.xml") != null && z.getEntry("mimetype") != null -> Fmt.ODT
                else -> Fmt.ZIP_TEXT
            }
        }
    }.getOrDefault(Fmt.ZIP_TEXT)
    if (starts("{\\rtf")) return Fmt.RTF
    val ext = f.extension.lowercase()
    val txt = decodeText(head).lowercase()
    return when {
        "<fictionbook" in txt -> Fmt.FB2
        ext in setOf("html", "htm", "xhtml") || "<html" in txt || "<!doctype html" in txt -> Fmt.HTML
        ext in setOf("md", "markdown") -> Fmt.MD
        else -> Fmt.TXT
    }
}

/** Вытаскивает чистый текст из файла любого поддерживаемого формата. Вызывать не с главного потока. */
fun readBookText(ctx: Context, f: File): String = when (sniff(f)) {
    Fmt.EPUB -> epubText(f)
    Fmt.FB2 -> fb2Text(f.readBytes())
    Fmt.DOCX -> docxText(f)
    Fmt.ODT -> odtText(f)
    Fmt.PDF -> pdfText(ctx, f)
    Fmt.RTF -> rtfText(String(f.readBytes(), Charsets.ISO_8859_1))
    Fmt.HTML -> htmlText(decodeText(f.readBytes()))
    Fmt.MD -> mdText(decodeText(f.readBytes()))
    Fmt.ZIP_TEXT -> zipText(ctx, f)
    Fmt.TXT -> {
        val b = f.readBytes()
        if (looksBinary(b)) throw IllegalArgumentException("Формат этого файла не поддерживается. Поддерживаются: $SUPPORTED.")
        unwrap(decodeText(b))
    }
}

// ---------- кодировки ----------

fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return String(bytes, Charsets.UTF_16)
    }
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251")) // старые русские тексты
    }
}

private fun looksBinary(b: ByteArray): Boolean {
    if (b.size >= 2) {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
    }
    val n = minOf(b.size, 4096)
    var bad = 0
    for (i in 0 until n) {
        val c = b[i].toInt() and 0xFF
        if (c < 9 || c in 14..31) bad++
    }
    return n > 0 && bad * 50 > n
}

/** Склеивает строки, разорванные переносами (в TXT и PDF), и убирает переносы слов. */
private fun unwrap(t: String): String =
    t.replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2")
        .replace(Regex("(?<![.!?…:»”\"])\\n(?=\\p{Ll})"), " ")

// ---------- форматы ----------

fun htmlText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.select("script, style, nav, footer, head").remove()
    val t = doc.select("h1, h2, h3, h4, h5, h6, p, li").joinToString("\n") { it.text() }
    return t.ifBlank { doc.body().text() }
}

private fun epubText(f: File): String = ZipFile(f).use { z ->
    fun xml(path: String) = Jsoup.parse(decodeText(z.getInputStream(z.getEntry(path)).readBytes()), "", Parser.xmlParser())
    val opfPath = xml("META-INF/container.xml").select("rootfile").first()!!.attr("full-path")
    val opf = xml(opfPath)
    val dir = opfPath.substringBeforeLast('/', "")
    val manifest = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
    opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }.joinToString("\n") { href ->
        val path = URLDecoder.decode(if (dir.isEmpty()) href else "$dir/$href", "UTF-8")
        val entry = z.getEntry(path) ?: return@joinToString ""
        htmlText(decodeText(z.getInputStream(entry).readBytes()))
    }
}

private fun fb2Text(bytes: ByteArray): String {
    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
    val enc = Regex("encoding=[\"']([^\"']+)[\"']").find(head)?.groupValues?.get(1)
    val xml = if (enc != null) runCatching { String(bytes, Charset.forName(enc)) }.getOrElse { decodeText(bytes) } else decodeText(bytes)
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    return doc.select("body").filter { it.attr("name") != "notes" }
        .joinToString("\n") { b -> b.select("p, subtitle").joinToString("\n") { it.text() } }
}

private fun docxText(f: File): String = ZipFile(f).use { z ->
    val xml = decodeText(z.getInputStream(z.getEntry("word/document.xml")).readBytes())
    Jsoup.parse(xml, "", Parser.xmlParser()).getElementsByTag("w:p")
        .joinToString("\n") { p -> p.getElementsByTag("w:t").joinToString("") { it.wholeText() } }
}

private fun odtText(f: File): String = ZipFile(f).use { z ->
    val xml = decodeText(z.getInputStream(z.getEntry("content.xml")).readBytes())
    Jsoup.parse(xml, "", Parser.xmlParser()).select("text|p, text|h").joinToString("\n") { it.wholeText() }
}

private fun pdfText(ctx: Context, f: File): String {
    PDFBoxResourceLoader.init(ctx.applicationContext)
    val raw = PDDocument.load(f).use { PDFTextStripper().getText(it) }
    return unwrap(raw)
}

private fun mdText(md: String): String = md.lines().joinToString("\n") { l ->
    l.trim()
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^>\\s?"), "")
        .replace(Regex("^([-*+]|\\d+\\.)\\s+"), "")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_`~]{1,3}"), "")
}

private fun zipText(ctx: Context, f: File): String = ZipFile(f).use { z ->
    val ok = setOf("fb2", "txt", "html", "htm", "md", "rtf")
    val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in ok }
        ?: throw IllegalArgumentException("В архиве нет файлов поддерживаемых форматов.")
    val tmp = File(ctx.cacheDir, "unz." + e.name.substringAfterLast('.'))
    z.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
    try { readBookText(ctx, tmp) } finally { tmp.delete() }
}

private fun rtfText(rtf: String): String {
    val skip = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "footnote", "object",
        "generator", "listtable", "listoverridetable", "revtbl", "latentstyles", "themedata", "datastore",
    )
    val cp = Regex("\\\\ansicpg(\\d+)").find(rtf)?.groupValues?.get(1)
        ?.let { runCatching { Charset.forName("windows-$it") }.getOrNull() } ?: Charset.forName("windows-1251")
    val out = StringBuilder()
    var i = 0
    var depth = 0
    var skipDepth = -1
    while (i < rtf.length) {
        val c = rtf[i]
        when (c) {
            '{' -> { depth++; i++ }
            '}' -> { if (depth == skipDepth) skipDepth = -1; depth--; i++ }
            '\\' -> {
                i++
                if (i >= rtf.length) break
                val n = rtf[i]
                when {
                    n == '\\' || n == '{' || n == '}' -> { if (skipDepth < 0) out.append(n); i++ }
                    n == '\'' -> {
                        val hex = rtf.substring(minOf(i + 1, rtf.length), minOf(i + 3, rtf.length))
                        if (skipDepth < 0) hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp)) }
                        i += 3
                    }
                    n == '*' -> { if (skipDepth < 0) skipDepth = depth; i++ }
                    n.isLetter() -> {
                        var j = i
                        while (j < rtf.length && rtf[j].isLetter()) j++
                        val word = rtf.substring(i, j)
                        var k = j
                        if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                            k++
                            while (k < rtf.length && rtf[k].isDigit()) k++
                        }
                        val param = rtf.substring(j, k).toIntOrNull()
                        if (k < rtf.length && rtf[k] == ' ') k++
                        i = k
                        if (word in skip && skipDepth < 0) skipDepth = depth
                        if (skipDepth < 0) when (word) {
                            "par", "line", "sect", "page" -> out.append('\n')
                            "tab" -> out.append(' ')
                            "emdash" -> out.append('—')
                            "endash" -> out.append('–')
                            "u" -> {
                                if (param != null) out.append((if (param < 0) param + 65536 else param).toChar())
                                i = minOf(i + 1, rtf.length) // пропускаем символ-заменитель
                            }
                        }
                    }
                    else -> i++
                }
            }
            '\n', '\r' -> i++
            else -> { if (skipDepth < 0) out.append(c); i++ }
        }
    }
    return out.toString()
}

// ---------- разбиение на абзацы и предложения ----------

fun splitParagraphs(text: String): List<List<String>> =
    text.split('\n').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.map { sentences(it) }

private val sentenceEnd = Regex("(?:(?<=[.!?…])|(?<=[.!?…][»”\"]))\\s+(?=[\\p{Lu}«“\"—–\\d])")

private fun sentences(para: String): List<String> =
    para.split(sentenceEnd).flatMap { s ->
        if (s.length <= 300) listOf(s) else pack(s.split(Regex("(?<=[,;:—])\\s+")), 300)
    }

private fun pack(parts: List<String>, max: Int): List<String> {
    val out = mutableListOf<String>()
    var cur = ""
    for (p in parts) {
        if (cur.isNotEmpty() && cur.length + 1 + p.length > max) { out.add(cur); cur = p }
        else cur = if (cur.isEmpty()) p else "$cur $p"
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out.flatMap { if (it.length > 400) it.chunked(400) else listOf(it) }
}
