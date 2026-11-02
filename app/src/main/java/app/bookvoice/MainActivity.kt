package app.bookvoice

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = intent
        setContent { AppTheme { App(this, incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF5A2DC8),
            secondary = Color(0xFF2575FC),
            tertiary = Color(0xFFFF9A5C),
            primaryContainer = Color(0xFFE8DDFF),
        ),
        content = content,
    )
}

fun friendlyError(m: String?): String = when (m) {
    NEED_MODEL -> "Для чтения без интернета нужно один раз скачать голос (около 100 МБ)."
    NEED_KEY -> "Для онлайн-озвучки введите API-ключ Yandex Cloud в настройках."
    null -> "Неизвестная ошибка"
    else -> m
}

@Composable
fun App(ctx: Context, incoming: MutableState<Intent?>) {
    var tab by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf<File?>(null) }
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    LaunchedEffect(incoming.value) {
        val i = incoming.value ?: return@LaunchedEffect
        incoming.value = null
        runCatching { withContext(Dispatchers.IO) { importIntent(ctx, i) } }
            .onSuccess { if (it != null) reading = it }
            .onFailure { Toast.makeText(ctx, "Не удалось открыть: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val nowPlaying by Playback.reader.playing.collectAsState()
    Scaffold(bottomBar = {
        Column {
            // мини-плеер: чтение продолжается, даже если вы вышли из книги
            if (reading == null && nowPlaying) Surface(tonalElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { reading = File(booksDir(ctx), Playback.fileName) }
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔊 ${Playback.title}", Modifier.weight(1f), maxLines = 1)
                    TextButton({ Playback.pause() }) { Text("Пауза") }
                }
            }
            if (reading == null) NavigationBar {
                listOf("📚" to "Библиотека", "🌐" to "Книги онлайн", "⚙️" to "Настройки").forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon, fontSize = 20.sp) }, label = { Text(label) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val book = reading
            if (book != null) ReaderScreen(ctx, prefs, book, openSettings = { reading = null; tab = 2 }, close = { reading = null })
            else when (tab) {
                0 -> LibraryScreen(ctx, open = { reading = it }, goCatalog = { tab = 1 })
                1 -> CatalogScreen(ctx, open = { reading = it })
                else -> SettingsScreen(ctx, prefs)
            }
        }
    }
}

// ======================= БИБЛИОТЕКА =======================

@Composable
fun LibraryScreen(ctx: Context, open: (File) -> Unit, goCatalog: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pos = remember { ctx.getSharedPreferences("pos", Context.MODE_PRIVATE) }
    var files by remember { mutableStateOf(listBooks(ctx)) }
    var addMenu by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { importUri(ctx, it) }.getOrNull() } }
            files = listBooks(ctx)
            Toast.makeText(ctx, if (added.isEmpty()) "Не удалось добавить файлы" else "Добавлено: ${added.size}", Toast.LENGTH_SHORT).show()
            if (added.size == 1) open(added[0])
        }
    }
    val pickFile = { picker.launch(arrayOf("*/*")) }

    Box(Modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            EmptyLibrary(onFile = { pickFile() }, onPaste = { pasteDialog = true }, onCatalog = goCatalog)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text("Моя библиотека", style = MaterialTheme.typography.headlineSmall) }
                items(files, key = { it.absolutePath }) { f ->
                    val total = pos.getInt("len_${f.name}", 0)
                    val at = pos.getInt("at_${f.name}", 0)
                    Card(Modifier.fillMaxWidth().clickable { open(f) }) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(displayTitle(f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(
                                    formatLabel(f) + if (total > 0) " · прочитано ${(at * 100L / total).coerceIn(0, 100)}%" else " · не открывали",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (total > 0) LinearProgressIndicator(
                                    progress = { (at.toFloat() / total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 10.dp),
                                )
                            }
                            TextButton({ toDelete = f }) { Text("✕") }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(onClick = { addMenu = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Text("＋ Добавить книгу")
            }
        }
    }

    if (addMenu) AlertDialog(
        onDismissRequest = { addMenu = false },
        title = { Text("Добавить книгу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ addMenu = false; pickFile() }, Modifier.fillMaxWidth()) { Text("📂 Файл с телефона") }
                OutlinedButton({ addMenu = false; pasteDialog = true }, Modifier.fillMaxWidth()) { Text("📋 Вставить текст") }
                OutlinedButton({ addMenu = false; goCatalog() }, Modifier.fillMaxWidth()) { Text("🌐 Бесплатные книги из интернета") }
                Text("Форматы: $SUPPORTED", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ addMenu = false }) { Text("Отмена") } },
    )

    if (pasteDialog) PasteDialog(ctx, onDismiss = { pasteDialog = false }) { title, text ->
        pasteDialog = false
        scope.launch {
            val f = withContext(Dispatchers.IO) { saveText(ctx, title, text) }
            files = listBooks(ctx)
            open(f)
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить из библиотеки?") },
            text = { Text(displayTitle(f)) },
            confirmButton = {
                TextButton({
                    if (Playback.fileName == f.name) Playback.pause()
                    f.delete()
                    pos.edit().remove("at_${f.name}").remove("len_${f.name}").apply()
                    files = listBooks(ctx)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton({ toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun EmptyLibrary(onFile: () -> Unit, onPaste: () -> Unit, onCatalog: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📖", fontSize = 56.sp)
        Text("Здесь пока пусто", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Добавьте книгу или любой текст — приложение прочитает его вслух ИИ-голосом.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onFile, Modifier.fillMaxWidth()) { Text("📂 Открыть файл с телефона") }
        OutlinedButton(onPaste, Modifier.fillMaxWidth()) { Text("📋 Вставить текст") }
        OutlinedButton(onCatalog, Modifier.fillMaxWidth()) { Text("🌐 Найти бесплатную книгу") }
        Text(
            "Форматы: $SUPPORTED",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
fun PasteDialog(ctx: Context, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вставить текст") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Текст") }, modifier = Modifier.fillMaxWidth().height(180.dp))
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { text = it.toString() }
                }) { Text("Вставить из буфера обмена") }
            }
        },
        confirmButton = { Button({ onSave(title, text) }, enabled = text.isNotBlank()) { Text("Читать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

// ======================= КНИГИ ОНЛАЙН =======================

@Composable
fun CatalogScreen(ctx: Context, open: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    val root = "https://www.gutenberg.org/ebooks.opds/"
    var stack by remember { mutableStateOf(listOf(root)) }
    var items by remember { mutableStateOf(listOf<OpdsEntry>()) }
    var nextUrl by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var query by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }

    LaunchedEffect(stack.last()) {
        msg = "Загрузка…"
        items = emptyList()
        nextUrl = null
        runCatching { withContext(Dispatchers.IO) { fetchFeed(stack.last()) } }
            .onSuccess { items = it.entries; nextUrl = it.next; title = it.title; msg = if (it.entries.isEmpty()) "Ничего не найдено" else "" }
            .onFailure { msg = "Нет связи или каталог недоступен (${it.message})" }
    }
    BackHandler(stack.size > 1) { stack = stack.dropLast(1) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Бесплатные книги", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Каталог Project Gutenberg: тысячи книг в открытом доступе (в основном на английском). Нажмите на книгу — она скачается в вашу библиотеку.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Название или автор") }, singleLine = true)
                Button({
                    if (query.isNotBlank()) stack = stack + "https://www.gutenberg.org/ebooks/search.opds/?query=${Uri.encode(query.trim())}"
                }) { Text("Найти") }
            }
        }
        if (stack.size > 1) item { TextButton({ stack = stack.dropLast(1) }) { Text("‹ Назад   $title") } }
        downloaded?.let { f ->
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ Скачано: ${displayTitle(f)}", Modifier.weight(1f))
                        Button({ open(f) }) { Text("Читать") }
                    }
                }
            }
        }
        if (msg.isNotEmpty()) item { Text(msg) }
        items(items) { e ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (e.epub != null) scope.launch {
                        msg = "Скачиваю «${e.title}»…"
                        runCatching { withContext(Dispatchers.IO) { downloadBook(ctx, e) } }
                            .onSuccess { downloaded = it; msg = "" }
                            .onFailure { msg = "Не получилось скачать: ${it.message}" }
                    } else e.nav?.let { stack = stack + it.href }
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium)
                    if (e.author.isNotEmpty()) Text(e.author, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (e.epub != null) "⬇" else "›", style = MaterialTheme.typography.titleLarge)
            }
        }
        nextUrl?.let { n ->
            item {
                OutlinedButton({
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { fetchFeed(n) } }
                            .onSuccess { items = items + it.entries; nextUrl = it.next }
                            .onFailure { msg = "Не удалось загрузить ещё: ${it.message}" }
                    }
                }, Modifier.fillMaxWidth()) { Text("Показать ещё") }
            }
        }
        item {
            TextButton({ showCustom = !showCustom }) { Text(if (showCustom) "Скрыть" else "＋ Свой каталог (OPDS-ссылка)") }
            if (showCustom) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it }, Modifier.weight(1f), label = { Text("Ссылка на каталог") }, singleLine = true)
                Button({ if (custom.isNotBlank()) stack = stack + custom.trim() }) { Text("Открыть") }
            }
        }
    }
}

// ======================= НАСТРОЙКИ =======================

@Composable
fun OptionCard(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsScreen(ctx: Context, p: SharedPreferences) {
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(p.getString("engine", "sherpa")!!) }
    var key by remember { mutableStateOf(p.getString("key", "")!!) }
    var voice by remember { mutableStateOf(p.getString("voice", "alena")!!) }
    var emotion by remember { mutableStateOf(p.getString("emotion", "neutral")!!) }
    var speed by remember { mutableFloatStateOf(p.getFloat("speed", 1f)) }
    var msg by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(modelInstalled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    val tester = remember { SpeechReader(scope) }
    val testing by tester.playing.collectAsState()
    val testErr by tester.error.collectAsState()
    DisposableEffect(Unit) { onDispose { tester.stop() } }

    fun save() = p.edit().putString("engine", engine).putString("key", key)
        .putString("voice", voice).putString("emotion", emotion).putFloat("speed", speed).apply()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Как читать вслух", style = MaterialTheme.typography.headlineSmall)

        OptionCard(engine == "sherpa", "Без интернета (бесплатно)", "Голос работает прямо на телефоне. Нужно один раз скачать голос.") { engine = "sherpa"; save() }
        if (engine == "sherpa") Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (installed) "✓ Голос установлен" else "Голос ещё не скачан (около 100 МБ)", Modifier.weight(1f))
            if (!installed) Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    msg = "Скачиваю голос, подождите…"
                    runCatching { withContext(Dispatchers.IO) { installPiperModel(ctx) } }
                        .onSuccess { installed = true; msg = "Голос установлен" }
                        .onFailure { msg = "Ошибка: ${it.message}" }
                    busy = false
                }
            }) { Text("Скачать") }
        }

        OptionCard(engine == "yandex", "Онлайн: Yandex SpeechKit", "Звучит естественнее, есть выбор голоса и интонации. Нужны интернет и API-ключ Yandex Cloud (платно сверх бесплатного лимита).") { engine = "yandex"; save() }
        if (engine == "yandex") {
            OutlinedTextField(key, { key = it; save() }, Modifier.fillMaxWidth(), label = { Text("API-ключ Yandex Cloud") }, singleLine = true)
            Text("Голос", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("alena", "filipp", "ermil", "jane", "omazh", "zahar").forEach { v ->
                    FilterChip(voice == v, { voice = v; save() }, { Text(v) })
                }
            }
            Text("Интонация", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("neutral" to "Спокойная", "good" to "Радостная", "evil" to "Строгая").forEach { (e, l) ->
                    FilterChip(emotion == e, { emotion = e; save() }, { Text(l) })
                }
            }
        }

        Text("Скорость чтения: ${"%.1f".format(speed)}×", style = MaterialTheme.typography.labelLarge)
        Slider(speed, { speed = (it * 10).toInt() / 10f; save() }, valueRange = 0.6f..1.6f)

        Button(onClick = {
            if (testing) tester.stop()
            else tester.play(listOf("Здравствуйте! Так будет звучать ваша книга."), 0) { createEngine(ctx, p) }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Остановить" else "▶ Проверить голос") }
        testErr?.let { Text(friendlyError(it), color = MaterialTheme.colorScheme.error) }
        if (msg.isNotEmpty()) Text(msg)

        HorizontalDivider()
        Text("Что умеет открывать приложение", style = MaterialTheme.typography.titleMedium)
        Text(
            "$SUPPORTED.\n\nФайлы можно открывать из любого приложения: выберите «Открыть в BookVoice» или «Поделиться». " +
                "Если у вас MOBI, AZW или DJVU, сначала конвертируйте их в EPUB или FB2. " +
                "PDF-сканы (фотографии страниц) без текстового слоя прочитать нельзя.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ======================= ЧИТАЛКА =======================

@Composable
fun ParagraphView(sents: List<String>, current: Int, fontSize: Int, onTap: (Int) -> Unit) {
    val bounds = remember(sents) {
        var s = 0
        sents.map { t -> val r = s until s + t.length; s += t.length + 1; r }
    }
    val text = remember(sents, current) {
        buildAnnotatedString {
            sents.forEachIndexed { i, t ->
                if (i == current) withStyle(SpanStyle(background = Color(0x66FFC857))) { append(t) } else append(t)
                if (i < sents.size - 1) append(" ")
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.45f).sp,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(sents) {
            detectTapGestures { offset ->
                layout?.let { l ->
                    val off = l.getOffsetForPosition(offset)
                    val i = bounds.indexOfFirst { off <= it.last + 1 }.let { if (it < 0) bounds.lastIndex else it }
                    onTap(i)
                }
            }
        },
    )
}

@Composable
fun ReaderScreen(ctx: Context, p: SharedPreferences, file: File, openSettings: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pos = remember { ctx.getSharedPreferences("pos", Context.MODE_PRIVATE) }
    val reader = Playback.reader
    var paras by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableIntStateOf(p.getInt("font", 18)) }
    var speed by remember { mutableFloatStateOf(p.getFloat("speed", 1f)) }
    var busy by remember { mutableStateOf(false) }
    var seek by remember { mutableStateOf<Float?>(null) }
    val list = rememberLazyListState()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val flat = remember(paras) { paras.flatten() }
    val starts = remember(paras) {
        IntArray(paras.size).also { arr -> var s = 0; paras.forEachIndexed { i, l -> arr[i] = s; s += l.size } }
    }
    val idx by reader.index.collectAsState()
    val playing by reader.playing.collectAsState()
    val err by reader.error.collectAsState()

    fun paraOf(i: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (starts[m] <= i) lo = m else hi = m - 1 }
        return lo
    }
    fun startAt(i: Int) {
        if (flat.isEmpty()) return
        // один раз спросим разрешение на уведомление с кнопками управления
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !p.getBoolean("asked_notif", false)
        ) {
            p.edit().putBoolean("asked_notif", true).apply()
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Playback.play(i.coerceIn(0, flat.size - 1))
    }

    LaunchedEffect(file) {
        if (Playback.isLoaded(file)) {
            // книга уже загружена (например, читает в фоне) — просто подключаемся
            paras = Playback.paras
            loading = false
        } else {
            loading = true
            runCatching { withContext(Dispatchers.IO) { splitParagraphs(readBookText(ctx, file)) } }
                .onSuccess {
                    paras = it
                    if (it.isEmpty()) {
                        loadErr = "В файле не нашлось текста. Если это скан или фотографии страниц, текст из него получить нельзя."
                    } else {
                        val total = it.sumOf { l -> l.size }
                        Playback.load(file, it, pos.getInt("at_${file.name}", 0).coerceIn(0, total - 1))
                        pos.edit().putInt("len_${file.name}", total).apply()
                    }
                }
                .onFailure { loadErr = "Не удалось открыть файл: ${it.message}" }
            loading = false
        }
    }
    LaunchedEffect(paras) { if (paras.isNotEmpty()) list.scrollToItem(paraOf(reader.index.value)) }
    val curPara = if (starts.isEmpty()) 0 else paraOf(idx)
    LaunchedEffect(curPara) { if (playing && paras.isNotEmpty()) list.animateScrollToItem(curPara) }
    BackHandler { close() } // чтение при этом продолжается в фоне

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(close) { Text("‹ Библиотека") }
            Spacer(Modifier.weight(1f))
            TextButton({ fontSize = (fontSize - 2).coerceAtLeast(12); p.edit().putInt("font", fontSize).apply() }) { Text("A−") }
            TextButton({ fontSize = (fontSize + 2).coerceAtMost(34); p.edit().putInt("font", fontSize).apply() }) { Text("A+") }
        }
        Text(
            displayTitle(file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadErr != null -> Text(loadErr!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        if (!playing && idx == 0) Text(
                            "Нажмите «Читать» или коснитесь любого предложения — чтение начнётся с него. Можно выйти из книги или выключить экран: чтение продолжится.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    itemsIndexed(paras) { pi, sents ->
                        val s0 = starts[pi]
                        ParagraphView(sents, if (idx >= s0 && idx < s0 + sents.size) idx - s0 else -1, fontSize) { si -> startAt(s0 + si) }
                    }
                }
            }
        }

        err?.let { e ->
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(friendlyError(e))
                    when (e) {
                        NEED_MODEL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { withContext(Dispatchers.IO) { installPiperModel(ctx) } }
                                        .onSuccess { reader.error.value = null; startAt(reader.index.value) }
                                        .onFailure { reader.error.value = "Не удалось скачать голос: ${it.message}" }
                                    busy = false
                                }
                            }) { Text(if (busy) "Скачиваю…" else "Скачать голос") }
                            TextButton({ reader.error.value = null; openSettings() }) { Text("Другие варианты") }
                        }
                        NEED_KEY -> Button(onClick = { reader.error.value = null; openSettings() }) { Text("Открыть настройки") }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (flat.size > 1) Slider(
                    value = seek ?: idx.toFloat(),
                    onValueChange = { seek = it },
                    onValueChangeFinished = {
                        val t = seek?.toInt()
                        seek = null
                        if (t != null) {
                            reader.index.value = t
                            if (playing) startAt(t) else if (paras.isNotEmpty()) scope.launch { list.scrollToItem(paraOf(t)) }
                        }
                    },
                    valueRange = 0f..(flat.size - 1).toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ Playback.skip(-1) }) { Text("‹") }
                    Button(
                        onClick = { if (playing) Playback.pause() else startAt(idx) },
                        enabled = flat.isNotEmpty(),
                        modifier = Modifier.height(52.dp).widthIn(min = 150.dp),
                    ) { Text(if (playing) "❚❚ Пауза" else "▶ Читать", fontSize = 18.sp) }
                    OutlinedButton({ Playback.skip(1) }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Скорость:", style = MaterialTheme.typography.bodySmall)
                    listOf(0.8f, 1f, 1.2f, 1.5f).forEach { s ->
                        FilterChip(abs(speed - s) < 0.05f, {
                            speed = s
                            p.edit().putFloat("speed", s).apply()
                            if (playing) startAt(idx)
                        }, { Text(if (s == 1f) "1×" else "$s×") })
                    }
                    TextButton(openSettings) {
                        Text(if (p.getString("engine", "sherpa") == "yandex") "Голос: онлайн ›" else "Голос: офлайн ›", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
