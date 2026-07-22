package com.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
 * Etappe 3: "Hey Jarvis" im Hintergrund.
 *
 * Dauerhafter Vordergrund-Dienst (sichtbare Benachrichtigung), der ueber
 * Porcupine lokal auf das Weckwort "Jarvis" lauscht. Kein Ton verlaesst das
 * Handy, bis das Weckwort faellt. Ablauf danach:
 *   Piep -> Frage aufnehmen (Stille-Erkennung, max. 10 s) -> Bestaetigungs-
 *   Piep -> Upload an /assistant (mit Retry/Idempotenz wie in der App) ->
 *   Jarvis' Stimmantwort abspielen -> weiterlauschen.
 * Waehrend Aufnahme und Antwort ist Porcupine PAUSIERT - sonst wuerde
 * Jarvis' eigene Stimme ("...Jarvis...") das Weckwort erneut ausloesen.
 */
class WakeWordService : Service() {

    private var porcupine: PorcupineManager? = null
    @Volatile private var beschaeftigt = false

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
            .setContentText("Sagen Sie „Jarvis“, um zu sprechen.")
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
        if (porcupine != null) return
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val accessKey = prefs.getString("picovoice", "") ?: ""
        if (accessKey.isEmpty()) {
            stopSelf()
            return
        }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f)
                .build(applicationContext) { _ -> aufWeckwort() }
            porcupine?.start()
        } catch (e: Exception) {
            // Falscher/abgelaufener AccessKey o. ae. - Dienst beendet sich,
            // die Benachrichtigung verschwindet (sichtbares Signal).
            stopSelf()
        }
    }

    private fun aufWeckwort() {
        if (beschaeftigt) return
        beschaeftigt = true
        thread {
            try {
                try { porcupine?.stop() } catch (_: Exception) {}
                ton(ToneGenerator.TONE_PROP_BEEP)
                val frage = nimmFrageAuf()
                if (frage != null && frage.length() > 0) {
                    ton(ToneGenerator.TONE_PROP_ACK)
                    frageJarvis(frage)
                }
            } finally {
                try { porcupine?.start() } catch (_: Exception) {}
                beschaeftigt = false
            }
        }
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
     *  das Lauschen fortgesetzt (sonst hoert Porcupine Jarvis' Stimme). */
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
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Exception) {
        }
        porcupine = null
        super.onDestroy()
    }
}
