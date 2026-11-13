package uz.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Doimiy tinglovchi xizmat.
 * Holatlar: UXLASH ("Jarvis" so'zini kutadi) -> TINGLASH (buyruq kutadi).
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var running = false
        private const val CHANNEL = "jarvis"
        private const val AWAKE_TIMEOUT_MS = 30_000L
        private val WAKE_WORDS = listOf("jarvis", "jarvez", "jarviz", "djarvis", "жарвис", "джарвис", "jarves")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var commands: CommandProcessor
    private var awake = false
    private var lastActivity = 0L
    private var speaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground("Uxlayapti — \"Salom Jarvis\" deng")
        tts = TextToSpeech(this, this)
        commands = CommandProcessor(this) { say(it) }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        listen()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- Ovoz bilan javob ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val uz = Locale("uz", "UZ")
        val r = tts.setLanguage(uz)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // O'zbek ovozi yo'q bo'lsa — turk ovozi lotin yozuvini eng yaxshi o'qiydi
            val tr = tts.setLanguage(Locale("tr", "TR"))
            if (tr < 0) tts.setLanguage(Locale.getDefault())
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { speaking = false; listen() } }
            @Deprecated("eski") override fun onError(id: String?) { main.post { speaking = false; listen() } }
        })
    }

    fun say(text: String) {
        main.post {
            speaking = true
            recognizer?.cancel()
            updateNotification(text)
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j${System.nanoTime()}")
            if (r == TextToSpeech.ERROR) { speaking = false; listen() }
        }
    }

    // ---------- Tinglash ----------
    private fun listen() {
        if (speaking || !running) return
        if (awake && System.currentTimeMillis() - lastActivity > AWAKE_TIMEOUT_MS) {
            awake = false
            updateNotification("Uxlayapti — \"Salom Jarvis\" deng")
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            main.postDelayed({ listen() }, 1000)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            handle(list)
        }

        override fun onError(error: Int) {
            if (speaking) return
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 250L
            main.postDelayed({ listen() }, delay)
        }

        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun handle(results: List<String>) {
        if (results.isEmpty()) { listen(); return }
        val best = results[0].lowercase().trim()
        val wakeHit = results.any { r -> WAKE_WORDS.any { r.lowercase().contains(it) } }

        if (!awake) {
            if (wakeHit) {
                awake = true
                lastActivity = System.currentTimeMillis()
                // "Jarvis, Telegramni och" — uyg'otish bilan birga buyruq ham bo'lsa
                val rest = stripWake(best)
                if (rest.length > 3 && !isGreeting(rest)) commands.execute(rest)
                else say("Nima gap? Eshitaman.")
            } else listen()
            return
        }

        lastActivity = System.currentTimeMillis()
        val cmd = stripWake(best)
        if (cmd.isBlank()) { say("Ha, eshitaman."); return }
        if (listOf("rahmat", "xayr", "uxla", "bo'ldi", "boldi", "yetadi").any { cmd.contains(it) }) {
            awake = false
            say("Arzimaydi. Kerak bo'lsam chaqiring.")
            return
        }
        commands.execute(cmd)
    }

    private fun stripWake(s: String): String {
        var r = s
        WAKE_WORDS.forEach { r = r.replace(it, " ") }
        return r.replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun isGreeting(s: String) =
        listOf("salom", "assalomu", "hey", "qalay", "привет").any { s.startsWith(it) } && s.split(" ").size <= 3

    // ---------- Bildirishnoma ----------
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }
}
