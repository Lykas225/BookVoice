package app.bookvoice

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class BookVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Playback.init(this)
    }
}

/**
 * Единый плеер на всё приложение: читает книгу независимо от экранов.
 * Экран читалки и уведомление управляют им и подписываются на его состояние.
 */
object Playback {
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val reader = SpeechReader(scope)

    var paras: List<List<String>> = emptyList()
        private set
    var sentences: List<String> = emptyList()
        private set
    var title: String = ""
        private set
    var fileName: String = ""
        private set
    @Volatile var serviceRunning = false

    private val settings get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pos get() = app.getSharedPreferences("pos", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // место чтения сохраняется даже когда экран читалки закрыт
        scope.launch {
            reader.index.collect { i ->
                if (fileName.isNotEmpty() && sentences.isNotEmpty()) pos.edit().putInt("at_$fileName", i).apply()
            }
        }
    }

    fun isLoaded(file: File) = fileName == file.name && paras.isNotEmpty()

    fun load(file: File, p: List<List<String>>, startIdx: Int) {
        if (fileName != file.name) reader.stop()
        title = displayTitle(file)
        fileName = file.name
        paras = p
        sentences = p.flatten()
        reader.index.value = startIdx
    }

    fun play(from: Int = reader.index.value) {
        if (sentences.isEmpty()) return
        if (!serviceRunning) {
            ContextCompat.startForegroundService(app, Intent(app, PlayerService::class.java).setAction(PlayerService.ACTION_START))
        }
        reader.play(sentences, from.coerceIn(0, sentences.size - 1)) { createEngine(app, settings) }
    }

    fun pause() = reader.stop()

    fun toggle() { if (reader.playing.value) pause() else play() }

    fun skip(delta: Int) {
        if (sentences.isEmpty()) return
        val t = (reader.index.value + delta).coerceIn(0, sentences.size - 1)
        reader.index.value = t
        if (reader.playing.value) play(t)
    }
}

/** Фоновая служба: держит чтение при выключенном экране, показывает уведомление с кнопками. */
class PlayerService : Service() {
    companion object {
        const val ACTION_START = "app.bookvoice.START"
        const val ACTION_TOGGLE = "app.bookvoice.TOGGLE"
        const val ACTION_NEXT = "app.bookvoice.NEXT"
        const val ACTION_PREV = "app.bookvoice.PREV"
        const val ACTION_STOP = "app.bookvoice.STOP"
        private const val CHANNEL = "reader"
        private const val NOTIF_ID = 1
    }

    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var foreground = false

    // наушники выдернули — ставим на паузу
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { Playback.pause() }
    }
    // звонок или другое приложение с аудио — ставим на паузу
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) Playback.pause()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Playback.serviceRunning = true
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Чтение вслух", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "BookVoice").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { Playback.play() }
                override fun onPause() { Playback.pause() }
                override fun onSkipToNext() { Playback.skip(1) }
                override fun onSkipToPrevious() { Playback.skip(-1) }
                override fun onStop() { Playback.pause(); shutdown() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(Playback.reader.playing, Playback.reader.index) { p, i -> p to i }
                .collect { (p, i) -> render(p, i) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!foreground) startFg(buildNotification(true, Playback.reader.index.value))
            ACTION_TOGGLE -> Playback.toggle()
            ACTION_NEXT -> Playback.skip(1)
            ACTION_PREV -> Playback.skip(-1)
            ACTION_STOP -> { Playback.pause(); shutdown() }
        }
        return START_NOT_STICKY
    }

    private fun render(playing: Boolean, idx: Int) {
        if (Playback.sentences.isEmpty()) return
        updateSession(playing)
        val n = buildNotification(playing, idx)
        if (playing) {
            requestFocus()
            acquireWake()
            if (!foreground) startFg(n) else nm.notify(NOTIF_ID, n)
        } else {
            releaseWake()
            abandonFocus()
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun startFg(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
            foreground = true
        }.onFailure { nm.notify(NOTIF_ID, n) }
    }

    private fun action(a: String): PendingIntent = PendingIntent.getService(
        this, a.hashCode(), Intent(this, PlayerService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(playing: Boolean, idx: Int): Notification {
        val total = Playback.sentences.size
        val snippet = Playback.sentences.getOrNull(idx).orEmpty().take(120)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun act(res: Int, label: String, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, res), label, action(a)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(Playback.title)
            .setContentText(snippet)
            .setSubText("${idx + 1} / $total")
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV))
            .addAction(
                if (playing) act(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE)
                else act(android.R.drawable.ic_media_play, "Читать", ACTION_TOGGLE)
            )
            .addAction(act(android.R.drawable.ic_media_next, "Вперёд", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateSession(playing: Boolean) {
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, Playback.title).build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                ).build()
        )
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener(focusListener).build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    private fun abandonFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookVoice:reading")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() {
        if (wake?.isHeld == true) wake?.release()
        wake = null
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        nm.cancel(NOTIF_ID)
        stopSelf()
    }

    override fun onDestroy() {
        Playback.serviceRunning = false
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        releaseWake()
        abandonFocus()
        session.release()
        super.onDestroy()
    }
}
