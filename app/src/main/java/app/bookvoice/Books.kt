package app.bookvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

fun booksDir(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }

fun listBooks(ctx: Context): List<File> =
    booksDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

fun displayTitle(f: File): String {
    val n = f.name.removeSuffix(".zip")
    return n.substringBeforeLast('.', n)
}

fun formatLabel(f: File): String =
    if (f.name.endsWith(".fb2.zip", true)) "FB2" else f.extension.uppercase().ifBlank { "TXT" }

fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    val base = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var n = 2
    while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
    return f
}

private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)

/** Копирует выбранный пользователем файл в библиотеку. Вызывать не с главного потока. */
fun importUri(ctx: Context, uri: Uri): File {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "book"
    val out = uniqueFile(booksDir(ctx), safeName(name))
    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
    return out
}

fun saveText(ctx: Context, title: String, text: String): File {
    val out = uniqueFile(booksDir(ctx), safeName(title.ifBlank { "Текст" }) + ".txt")
    out.writeText(text, Charsets.UTF_8)
    return out
}

/** Обрабатывает «Открыть в…» и «Поделиться» из других приложений. */
@Suppress("DEPRECATION")
fun importIntent(ctx: Context, i: Intent): File? = when (i.action) {
    Intent.ACTION_VIEW -> i.data?.let { importUri(ctx, it) }
    Intent.ACTION_SEND -> {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val txt = i.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uri != null -> importUri(ctx, uri)
            txt != null -> saveText(ctx, "Текст", txt)
            else -> null
        }
    }
    else -> null
}

/** Скачивает EPUB из записи каталога. Вызывать не с главного потока. */
fun downloadBook(ctx: Context, e: OpdsEntry): File {
    val link = e.epub ?: error("В этой записи нет EPUB")
    val name = safeName(e.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").ifBlank { "book" }.take(80)) + ".epub"
    val out = uniqueFile(booksDir(ctx), name)
    http.newCall(Request.Builder().url(link.href).build()).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code}" }
        out.outputStream().use { r.body!!.byteStream().copyTo(it) }
    }
    return out
}

/** Скачивает и распаковывает русский голос Piper для Sherpa-ONNX (~60–100 МБ). */
fun installPiperModel(ctx: Context, name: String = PIPER_MODEL): File {
    val root = File(ctx.filesDir, "tts").apply { mkdirs() }
    val target = File(root, name)
    if (target.exists() && File(target, "tokens.txt").exists()) return target
    target.deleteRecursively()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$name.tar.bz2"
    try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            TarArchiveInputStream(BZip2CompressorInputStream(r.body!!.byteStream().buffered())).use { tar ->
                generateSequence { tar.nextEntry }.forEach { e ->
                    val f = File(root, e.name)
                    require(f.canonicalPath.startsWith(root.canonicalPath)) { "Плохой путь в архиве" }
                    if (e.isDirectory) f.mkdirs()
                    else { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it) } }
                }
            }
        }
    } catch (t: Throwable) {
        target.deleteRecursively()
        throw t
    }
    return target
}
