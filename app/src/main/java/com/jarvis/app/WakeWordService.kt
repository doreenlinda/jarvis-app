package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Etappe 3: "Hey Jarvis" im Hintergrund - seit v0.6 ueber openWakeWord
 * statt Porcupine (Picovoice hat sein kostenloses Konto zum 30.06.2026
 * abgeschafft; openWakeWord braucht weder Konto noch AccessKey).
 *
 * Dauerhafter Vordergrund-Dienst (sichtbare Benachrichtigung), der lokal
 * auf "Hey Jarvis" lauscht. Kein Ton verlaesst das Handy, bis das Weckwort
 * faellt. Ablauf danach:
 *   Piep -> Frage aufnehmen (Stille-Erkennung, max. 10 s) -> Bestaetigungs-
 *   Piep -> Upload an /assistant (mit Retry/Idempotenz wie in der App) ->
 *   Jarvis' Stimmantwort abspielen -> weiterlauschen.
 * Waehrend Aufnahme und Antwort ist das Lauschen PAUSIERT (Mikrofon wird
 * fuer die Aufnahme gebraucht, und Jarvis' eigene Stimme darf das Weckwort
 * nicht erneut ausloesen); danach werden die Erkennungs-Puffer geleert.
 */
class WakeWordService : Service() {

    companion object {
        private const val SAMPLE_RATE = 16000
        // Ab diesem Score gilt das Weckwort als erkannt (openWakeWord-
        // Standard 0,5; hoeher = weniger Fehlalarme, dafuer muss man
        // deutlicher sprechen).
        private const val SCHWELLE = 0.5f
    }

    @Volatile private var aktiv = false
    private var lauschThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var engine: OpenWakeWord? = null

    /** Sichtbare Diagnose: Der Dienst meldet seinen Zustand in die
     *  SharedPreferences, die MainActivity zeigt ihn live an. Auf dem
     *  Handy gibt es kein lesbares Log - Fehler duerfen deshalb NIE
     *  stumm verschluckt werden (Lektion aus dem ersten v0.6-Test:
     *  "kein Piep, Benachrichtigung nie da" war ohne Diagnose nicht
     *  eingrenzbar). */
    private fun meldeStatus(text: String, ueberschreibeFehler: Boolean = true) {
        try {
            val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            if (!ueberschreibeFehler) {
                // Eine FEHLER-Meldung muss stehen bleiben, bis Doreen sie
                // gelesen hat - "Dienst beendet." darf sie nicht verdraengen
                // (genau das passierte beim ersten v0.7-Test: die Meldung
                // "ploppte nur kurz auf").
                val aktuell = prefs.getString("wake_status", "") ?: ""
                if (aktuell.startsWith("FEHLER")) return
            }
            prefs.edit().putString("wake_status", text).apply()
        } catch (_: Exception) {}
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starteVordergrund()
        starteLauschen()
        return START_STICKY
    }

    private fun starteVordergrund() {
        val kanalId = "jarvis_wake"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(kanalId, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, kanalId)
            .setContentTitle("Jarvis lauscht")
            .setContentText("Sagen Sie „Hey Jarvis“, um zu sprechen.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun starteLauschen() {
        if (aktiv) return
        meldeStatus("Dienst gestartet, lade Erkennungsmodelle …")
        try {
            engine = OpenWakeWord(applicationContext)
        } catch (t: Throwable) {
            // Throwable, nicht nur Exception: ein UnsatisfiedLinkError der
            // TensorFlow-Lite-Bibliothek waere sonst ein stummer Absturz.
            meldeStatus("FEHLER beim Laden der Erkennung: $t")
            stopSelf()
            return
        }
        meldeStatus("Modelle geladen, Lauschen beginnt …")
        aktiv = true
        lauschThread = thread {
            try {
                while (aktiv) {
                    if (!lauscheBisWeckwort()) {
                        // Mikrofon-Aussetzer (z. B. andere App hatte es kurz):
                        // kurz warten und erneut versuchen statt still
                        // aufzugeben, waehrend die Benachrichtigung weiter
                        // "Jarvis lauscht" behauptet.
                        if (aktiv) Thread.sleep(3000)
                        continue
                    }
                    // Weckwort erkannt: Mikrofon ist freigegeben, dann der
                    // bewaehrte Frage-/Antwort-Ablauf (unveraendert aus der
                    // Porcupine-Zeit).
                    meldeStatus("Weckwort erkannt – ich höre Ihre Frage …")
                    ton(ToneGenerator.TONE_PROP_BEEP)
                    val frage = nimmFrageAuf()
                    if (frage != null && frage.length() > 0) {
                        ton(ToneGenerator.TONE_PROP_ACK)
                        meldeStatus("Frage aufgenommen, sende an Jarvis …")
                        frageJarvis(frage)
                    } else {
                        meldeStatus("Keine Frage gehört – ich lausche weiter.")
                    }
                    // Puffer leeren, damit die eigene Aufnahme/Stimme keinen
                    // Fehlalarm hinterlaesst; danach lauscht die Schleife weiter.
                    engine?.reset()
                }
            } finally {
                gibMikrofonFrei()
            }
        }
    }

    /**
     * Lauscht blockierend, bis das Weckwort faellt (true) oder der Dienst
     * gestoppt wird bzw. das Mikrofon nicht verfuegbar ist (false).
     */
    private fun lauscheBisWeckwort(): Boolean {
        val eng = engine ?: return false
        val minPuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minPuffer, OpenWakeWord.BLOCK_SAMPLES * 2 * 4)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            // Ohne Mikrofon-Berechtigung o. ae. beendet sich der Dienst.
            meldeStatus("FEHLER: Mikrofon nicht verfügbar (Berechtigung? Andere App?)")
            aktiv = false
            stopSelf()
            return false
        }
        audioRecord = rec
        rec.startRecording()

        val block = ShortArray(OpenWakeWord.BLOCK_SAMPLES)
        // Diagnose: hoechster Erkennungswert der letzten ~2,5 Sekunden -
        // damit ist in der App sichtbar, OB die Erkennung lebt und wie nah
        // ein gesprochenes "Hey Jarvis" an die Schwelle kommt.
        var maxScore = 0f
        var bloecke = 0
        try {
            while (aktiv) {
                var gelesen = 0
                while (aktiv && gelesen < block.size) {
                    val n = rec.read(block, gelesen, block.size - gelesen)
                    if (n <= 0) {
                        meldeStatus("FEHLER: Mikrofon liefert keine Daten (Code $n) – neuer Versuch in 3 s")
                        return false
                    }
                    gelesen += n
                }
                if (!aktiv) return false
                val score = try {
                    eng.verarbeite(block)
                } catch (t: Throwable) {
                    meldeStatus("FEHLER in der Erkennung: $t")
                    return false
                }
                if (score > SCHWELLE) return true
                maxScore = maxOf(maxScore, score)
                if (++bloecke >= 32) {  // ~2,5 s
                    meldeStatus("Lauscht … höchster Erkennungswert zuletzt: " +
                        String.format("%.2f", maxScore) + " (Schwelle ${SCHWELLE})")
                    maxScore = 0f
                    bloecke = 0
                }
            }
            return false
        } finally {
            gibMikrofonFrei()
        }
    }

    private fun gibMikrofonFrei() {
        val rec = audioRecord ?: return
        audioRecord = null
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
    }

    /**
     * Nimmt die Frage nach dem Weckwort auf. Einfache Stille-Erkennung:
     * Ende, sobald nach gesprochenen Worten ~1,3 s Ruhe herrscht;
     * Obergrenze 10 s. Wurde gar nicht gesprochen, wird nichts gesendet.
     */
    private fun nimmFrageAuf(): File? {
        val datei = File(cacheDir, "wake_frage.m4a")
        @Suppress("DEPRECATION")
        val rec = MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128000)
            rec.setAudioSamplingRate(44100)
            rec.setOutputFile(datei.absolutePath)
            rec.prepare()
            rec.start()

            var laufzeit = 0
            var stilleSeit = 0
            var gesprochen = false
            while (laufzeit < 10_000) {
                Thread.sleep(250)
                laufzeit += 250
                val pegel = rec.maxAmplitude
                if (pegel > 1500) {
                    gesprochen = true
                    stilleSeit = 0
                } else if (gesprochen) {
                    stilleSeit += 250
                    if (stilleSeit >= 1300) break
                }
            }
            rec.stop()
            rec.release()
            if (gesprochen) datei else null
        } catch (e: Exception) {
            try { rec.release() } catch (_: Exception) {}
            null
        }
    }

    /** Schickt die Aufnahme an /assistant - gleiche Retry-/Idempotenz-Logik
     *  wie der Sprechen-Knopf in der App. */
    private fun frageJarvis(audio: File) {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val base = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (base.isEmpty() || key.isEmpty()) return
        val requestId = UUID.randomUUID().toString()

        for (versuch in 1..3) {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("key", key)
                    .addFormDataPart("request_id", requestId)
                    .addFormDataPart(
                        "audio", "frage.m4a", audio.asRequestBody("audio/mp4".toMediaType())
                    )
                    .build()
                val request = Request.Builder()
                    .url("$base/assistant")
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        ton(ToneGenerator.TONE_SUP_ERROR)
                        return
                    }
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val audioB64 = if (json.isNull("audio_base64")) null
                                   else json.optString("audio_base64", null)
                    if (audioB64 != null) spieleAntwort(audioB64)
                }
                return
            } catch (e: IOException) {
                // Kurzer Netz-/VPN-Aussetzer: warten und mit derselben
                // request_id erneut - der Server liefert dann die schon
                // fertige Antwort aus dem Idempotenz-Cache.
                if (versuch >= 3) ton(ToneGenerator.TONE_SUP_ERROR)
                else Thread.sleep(2500)
            } catch (e: Exception) {
                ton(ToneGenerator.TONE_SUP_ERROR)
                return
            }
        }
    }

    /** Spielt die Antwort ab und BLOCKIERT bis zum Ende - erst danach wird
     *  das Lauschen fortgesetzt (sonst hoert die Erkennung Jarvis' Stimme). */
    private fun spieleAntwort(b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val tmp = File(cacheDir, "wake_antwort.mp3")
            tmp.writeBytes(bytes)
            val fertig = Object()
            val mp = MediaPlayer()
            mp.setDataSource(tmp.absolutePath)
            mp.setOnCompletionListener {
                synchronized(fertig) { fertig.notifyAll() }
                it.release()
            }
            mp.prepare()
            mp.start()
            synchronized(fertig) { fertig.wait(120_000) }
        } catch (_: Exception) {
        }
    }

    private fun ton(art: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(art, 150)
            Thread.sleep(220)
            tg.release()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        aktiv = false
        meldeStatus("Dienst beendet.", ueberschreibeFehler = false)
        gibMikrofonFrei()
        try { lauschThread?.join(1000) } catch (_: Exception) {}
        lauschThread = null
        try { engine?.schliessen() } catch (_: Exception) {}
        engine = null
        super.onDestroy()
    }
}
