package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 * Etappe 2, Schicht 1: Sprechen mit Jarvis.
 * - Text senden bleibt erhalten (Schicht 0).
 * - NEU: Aufnahme-Knopf -> Mikrofon aufnehmen (MediaRecorder, .m4a) ->
 *   multipart-Upload an /assistant -> Antwort als Text anzeigen UND
 *   Jarvis' Stimmantwort (audio_base64 vom Server) abspielen.
 * Beide Wege laufen jetzt ueber OkHttp (ein HTTP-Client, multipart).
 */
class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // Whisper + Antwort kann dauern
        .build()

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var player: MediaPlayer? = null

    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var textField: EditText
    private lateinit var answerView: TextView
    private lateinit var talkButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.serverUrl)
        keyField = findViewById(R.id.accessKey)
        textField = findViewById(R.id.messageText)
        answerView = findViewById(R.id.answerView)
        talkButton = findViewById(R.id.talkButton)
        val sendButton = findViewById<Button>(R.id.sendButton)

        // URL und Schluessel merken - nur einmal eintippen.
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        urlField.setText(prefs.getString("url", ""))
        keyField.setText(prefs.getString("key", ""))

        sendButton.setOnClickListener {
            val msg = textField.text.toString().trim()
            if (msg.isEmpty()) { answerView.text = "Bitte eine Nachricht eingeben."; return@setOnClickListener }
            if (!checkFields()) return@setOnClickListener
            answerView.text = "Sende …"
            thread { requestJarvis(text = msg, audio = null) }
        }

        talkButton.setOnClickListener {
            if (isRecording) {
                stopAndSend()
            } else {
                startRecording()
            }
        }
    }

    /** Prueft URL+Key, speichert sie und gibt true zurueck, wenn beides da ist. */
    private fun checkFields(): Boolean {
        val base = urlField.text.toString().trim().trimEnd('/')
        val key = keyField.text.toString().trim()
        if (base.isEmpty() || key.isEmpty()) {
            answerView.text = "Bitte Server-URL und Schluessel ausfuellen."
            return false
        }
        getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
            .putString("url", base).putString("key", key).apply()
        return true
    }

    // --- Aufnahme -----------------------------------------------------------

    private fun startRecording() {
        if (!checkFields()) return
        // Mikrofon-Berechtigung sicherstellen.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            answerView.text = "Bitte die Mikrofon-Berechtigung erlauben und dann erneut auf Sprechen tippen."
            return
        }
        try {
            val file = File(cacheDir, "aufnahme.m4a")
            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128000)
            rec.setAudioSamplingRate(44100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            audioFile = file
            isRecording = true
            talkButton.text = "⏹ Aufnahme läuft – zum Stoppen tippen"
            answerView.text = "Ich höre zu …"
        } catch (e: Exception) {
            answerView.text = "Aufnahme konnte nicht starten: ${e.message}"
            releaseRecorder()
        }
    }

    private fun stopAndSend() {
        val file = try {
            recorder?.stop()
            audioFile
        } catch (e: Exception) {
            null
        } finally {
            releaseRecorder()
        }
        talkButton.text = "🎤 Sprechen"
        if (file == null || !file.exists() || file.length() == 0L) {
            answerView.text = "Die Aufnahme war leer – bitte nochmal versuchen."
            return
        }
        answerView.text = "Sende …"
        thread { requestJarvis(text = null, audio = file) }
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }

    // --- Anfrage an den Server ---------------------------------------------

    private fun requestJarvis(text: String?, audio: File?) {
        val base = urlField.text.toString().trim().trimEnd('/')
        val key = keyField.text.toString().trim()
        // Eine Kennung fuer diese Sende-Aktion - bleibt ueber alle Wiederhol-
        // Versuche GLEICH, damit der Server bei einem Retry die schon fertige
        // Antwort zurueckgibt statt neu zu verarbeiten (Idempotenz).
        val requestId = UUID.randomUUID().toString()
        val maxVersuche = 3
        var letzterFehler = ""

        for (versuch in 1..maxVersuche) {
            try {
                val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("key", key)
                    .addFormDataPart("request_id", requestId)
                if (!text.isNullOrEmpty()) bodyBuilder.addFormDataPart("text", text)
                if (audio != null) bodyBuilder.addFormDataPart(
                    "audio", "aufnahme.m4a", audio.asRequestBody("audio/mp4".toMediaType())
                )

                val request = Request.Builder()
                    .url("$base/assistant")
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .post(bodyBuilder.build())
                    .build()

                client.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        runOnUiThread { answerView.text = "Fehler ${resp.code}: $respBody" }
                        return
                    }
                    val json = JSONObject(respBody)
                    val answer = json.optString("text", respBody)
                    val audioB64 = if (json.isNull("audio_base64")) null
                                   else json.optString("audio_base64", null)
                    runOnUiThread { answerView.text = answer }
                    if (audioB64 != null) playAudio(audioB64)
                }
                return  // Erfolg
            } catch (e: IOException) {
                // Verbindungsabbruch (z. B. kurzer VPN-Aussetzer). Kurz warten
                // und mit DERSELBEN request_id erneut versuchen.
                letzterFehler = e.message ?: "Verbindung unterbrochen"
                if (versuch < maxVersuche) {
                    runOnUiThread {
                        answerView.text = "Verbindung kurz gestört – ich versuche es erneut … ($versuch)"
                    }
                    try { Thread.sleep(2500) } catch (_: InterruptedException) {}
                }
            } catch (e: Exception) {
                runOnUiThread { answerView.text = "Fehler: ${e.message}" }
                return
            }
        }
        runOnUiThread {
            answerView.text = "Verbindung ließ sich nicht stabil aufbauen: $letzterFehler"
        }
    }

    // --- Sprachantwort abspielen -------------------------------------------

    private fun playAudio(b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val tmp = File(cacheDir, "antwort.mp3")
            tmp.writeBytes(bytes)
            player?.release()
            val mp = MediaPlayer()
            mp.setDataSource(tmp.absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            // Antworttext steht ja schon da - Tonausfall ist nicht schlimm.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
