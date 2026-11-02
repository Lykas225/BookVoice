package app.bookvoice

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.FormBody
import okhttp3.Request
import java.io.File

/** Готовый звук: 16-bit mono PCM. */
class Pcm(val samples: ShortArray, val rate: Int)

/** Любой движок озвучки превращает текст в PCM. Новые движки добавляются реализацией этого интерфейса. */
interface TtsEngine {
    suspend fun synthesize(text: String): Pcm
}

/** Онлайн: Yandex SpeechKit (REST v1). Голос и эмоция задаются в настройках. */
class YandexEngine(
    private val apiKey: String,
    private val voice: String,
    private val emotion: String,
    private val speed: Float,
) : TtsEngine {
    override suspend fun synthesize(text: String): Pcm = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("text", text).add("lang", "ru-RU").add("voice", voice)
            .add("emotion", emotion).add("speed", speed.toString())
            .add("format", "lpcm").add("sampleRateHertz", "48000").build()
        val req = Request.Builder()
            .url("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize")
            .header("Authorization", "Api-Key $apiKey").post(body).build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "Yandex TTS: HTTP ${r.code}" }
            val b = r.body!!.bytes()
            Pcm(ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }, 48000)
        }
    }
}

/** Офлайн: Sherpa-ONNX + голос Piper. Нужен AAR в app/libs и установленная модель. */
class SherpaEngine(modelDir: File, var speed: Float, private val speaker: Int = 0) : TtsEngine {
    private val tts: OfflineTts

    init {
        val onnx = modelDir.listFiles { f -> f.extension == "onnx" }!!.first().absolutePath
        val vits = OfflineTtsVitsModelConfig(
            model = onnx,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
        )
        tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu")))
    }

    override suspend fun synthesize(text: String): Pcm = withContext(Dispatchers.Default) {
        val a = tts.generate(text = text, sid = speaker, speed = speed)
        Pcm(ShortArray(a.samples.size) { (a.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }, a.sampleRate)
    }
}

class PcmPlayer {
    private var track: AudioTrack? = null
    private var rate = 0

    /** Блокирующая запись: возвращается, когда звук поместился в буфер. */
    fun write(p: Pcm) {
        if (track == null || rate != p.rate) {
            track?.release()
            rate = p.rate
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(min * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        }
        val t = track ?: return
        t.write(p.samples, 0, p.samples.size)
    }

    fun release() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.release() }
    }
}

/** Режет книгу на предложения, синтезирует с опережением (до 3 вперёд) и проигрывает по очереди. */
class SpeechReader(private val scope: CoroutineScope) {
    val index = MutableStateFlow(0)
    val playing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var player: PcmPlayer? = null

    fun play(sentences: List<String>, from: Int, createEngine: suspend () -> TtsEngine) {
        stop()
        playing.value = true
        error.value = null
        val pl = PcmPlayer().also { player = it }
        job = scope.launch(Dispatchers.IO) {
            try {
                val engine = createEngine()
                coroutineScope {
                    val queue = Channel<Pair<Int, Pcm>>(3)
                    launch {
                        for (i in from until sentences.size) queue.send(i to engine.synthesize(sentences[i]))
                        queue.close()
                    }
                    for ((i, pcm) in queue) {
                        index.value = i
                        pl.write(pcm)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error.value = t.message ?: t.toString()
            } finally {
                pl.release()
                if (player === pl) { player = null; playing.value = false }
            }
        }
    }

    fun stop() {
        job?.cancel()
        player?.release()
        player = null
        playing.value = false
    }
}

const val PIPER_MODEL = "vits-piper-ru_RU-irina-medium"
const val NEED_MODEL = "NEED_MODEL"
const val NEED_KEY = "NEED_KEY"

private var sherpaCache: SherpaEngine? = null

fun modelInstalled(ctx: Context) = File(ctx.filesDir, "tts/$PIPER_MODEL/tokens.txt").exists()

/** Собирает движок озвучки по настройкам. Офлайн-модель загружается один раз и переиспользуется. */
fun createEngine(ctx: Context, p: SharedPreferences): TtsEngine {
    val speed = p.getFloat("speed", 1f)
    return if (p.getString("engine", "sherpa") == "yandex") {
        val key = p.getString("key", "").orEmpty().trim()
        if (key.isEmpty()) throw IllegalStateException(NEED_KEY)
        YandexEngine(key, p.getString("voice", "alena")!!, p.getString("emotion", "neutral")!!, speed)
    } else {
        if (!modelInstalled(ctx)) throw IllegalStateException(NEED_MODEL)
        val e = sherpaCache ?: SherpaEngine(File(ctx.filesDir, "tts/$PIPER_MODEL"), speed).also { sherpaCache = it }
        e.speed = speed
        e
    }
}
