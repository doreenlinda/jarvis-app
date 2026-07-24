package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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

    // Live-Diagnose des "Hey Jarvis"-Dienstes: Der Dienst schreibt seinen
    // Zustand (laeuft / Erkennungswert / Fehler im Klartext) in die
    // SharedPreferences, wir zeigen ihn jede Sekunde an. Ohne das waren
    // Dienst-Fehler auf dem Handy komplett unsichtbar (v0.6-Lektion).
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statusView: TextView? = null
    private val statusRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            if (prefs.getBoolean("wake_aktiv", false)) {
                val status = prefs.getString("wake_status", "") ?: ""
                statusView?.text = if (status.isEmpty()) "Hey Jarvis: (noch keine Meldung vom Dienst)"
                                   else "Hey Jarvis: $status"
                statusHandler.postDelayed(this, 1000)
            } else {
                statusView?.text = ""
            }
        }
    }

    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var textField: EditText
    private lateinit var answerView: TextView
    private lateinit var talkButton: Button

    // --- Kamera ("Zeigen/Scannen"): System-Kamera macht das Foto in voller
    // Aufloesung in eine FileProvider-Datei, danach wird es verkleinert und
    // an Jarvis geschickt. Eine Frage im Nachrichten-Feld geht mit. ---
    private var fotoRoh: File? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val roh = fotoRoh
            if (!ok || roh == null || !roh.exists() || roh.length() == 0L) {
                answerView.text = "Kein Foto aufgenommen."
                return@registerForActivityResult
            }
            answerView.text = "Foto wird vorbereitet …"
            val frage = textField.text.toString().trim()
            thread {
                val klein = skaliereFoto(roh)
                if (klein == null) {
                    runOnUiThread { answerView.text = "Foto konnte nicht verarbeitet werden." }
                } else {
                    runOnUiThread { answerView.text = "Sende …" }
                    requestJarvis(
                        text = if (frage.isEmpty()) null else frage,
                        audio = null,
                        image = klein,
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.serverUrl)
        keyField = findViewById(R.id.accessKey)
        textField = findViewById(R.id.messageText)
        answerView = findViewById(R.id.answerView)
        talkButton = findViewById(R.id.talkButton)
        statusView = findViewById(R.id.wakeStatusView)
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

        findViewById<Button>(R.id.cameraButton).setOnClickListener {
            if (!checkFields()) return@setOnClickListener
            try {
                val f = File(cacheDir, "foto_roh.jpg")
                if (f.exists()) f.delete()
                fotoRoh = f
                val uri = FileProvider.getUriForFile(this, "com.jarvis.app.fileprovider", f)
                takePicture.launch(uri)
            } catch (e: Exception) {
                answerView.text = "Kamera konnte nicht gestartet werden: ${e.message}"
            }
        }

        // --- "Hey Jarvis" im Hintergrund: startet/stoppt den Lausch-Dienst.
        // Seit v0.6 ueber openWakeWord - KEIN Picovoice-AccessKey mehr noetig
        // (Picovoice hat sein kostenloses Konto zum 30.06.2026 abgeschafft).
        val wakeButton = findViewById<Button>(R.id.wakeButton)

        fun setzeWakeText() {
            wakeButton.text = if (prefs.getBoolean("wake_aktiv", false))
                "🔴 „Hey Jarvis” stoppen" else "🟢 „Hey Jarvis” aktivieren"
        }
        setzeWakeText()

        wakeButton.setOnClickListener {
            if (prefs.getBoolean("wake_aktiv", false)) {
                stopService(Intent(this, WakeWordService::class.java))
                prefs.edit().putBoolean("wake_aktiv", false).apply()
                setzeWakeText()
                answerView.text = "Hintergrund-Lauschen gestoppt."
                return@setOnClickListener
            }
            if (!checkFields()) return@setOnClickListener

            // Noetige Berechtigungen: Mikrofon + (ab Android 13) Benachrichtigung
            val fehlend = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) fehlend += Manifest.permission.RECORD_AUDIO
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) fehlend += Manifest.permission.POST_NOTIFICATIONS
            if (fehlend.isNotEmpty()) {
                requestPermissions(fehlend.toTypedArray(), 2)
                answerView.text = "Bitte die Berechtigungen erlauben und dann erneut aktivieren."
                return@setOnClickListener
            }

            prefs.edit().putString("wake_status", "").apply()
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            prefs.edit().putBoolean("wake_aktiv", true).apply()
            setzeWakeText()
            answerView.text = "Jarvis lauscht jetzt im Hintergrund. Sagen Sie „Hey Jarvis”, " +
                "warten Sie auf den Piep, und sprechen Sie dann Ihre Frage. " +
                "Der Dienst-Zustand erscheint unter den Knöpfen."
            statusHandler.removeCallbacks(statusRunnable)
            statusHandler.post(statusRunnable)
        }
    }

    /**
     * Skaliert das Kamerafoto auf maximal ~2000 Pixel Kante (haelt es unter
     * dem Server-Limit, reicht fuer Kleingedrucktes auf Etiketten) und
     * wendet die EXIF-Drehung an - sonst kaeme ein hochkant aufgenommenes
     * Dokument um 90 Grad gedreht bei der Auswertung an.
     */
    private fun skaliereFoto(roh: File): File? {
        return try {
            val grenzen = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(roh.absolutePath, grenzen)
            val maxKante = maxOf(grenzen.outWidth, grenzen.outHeight)
            var sample = 1
            while (maxKante / sample > 2000) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(roh.absolutePath, opts) ?: return null

            val drehung = when (
                ExifInterface(roh.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (drehung != 0f) {
                val m = Matrix().apply { postRotate(drehung) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            val ziel = File(cacheDir, "foto_klein.jpg")
            ziel.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            ziel
        } catch (e: Exception) {
            null
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

    private fun requestJarvis(text: String?, audio: File?, image: File? = null) {
        val base = urlField.text.toString().trim().trimEnd('/')
        val key = keyField.text.toString().trim()

        // Satzweise Antwort: Jarvis faengt an zu sprechen, sobald der erste
        // Satz steht, statt die komplette Antwort abzuwarten (server-seitig
        // ~4,9 s statt ~9,2 s bis zum ersten Ton). Fotos gehen bewusst weiter
        // den klassischen Weg (Vision streamt nicht sinnvoll). Kommt kein
        // einziger Block an, faellt es unten auf /assistant zurueck – die
        // Antwort kann also nie ganz ausbleiben.
        if (image == null) {
            val gestreamt = StreamClient.ask(
                client = client, base = base, key = key,
                text = text, audio = audio, cacheDir = cacheDir,
                blockiereBisGesprochen = false,
                onText = { laufend -> runOnUiThread { answerView.text = laufend } },
            )
            if (gestreamt) return
            runOnUiThread { answerView.text = "Sende … (klassisch)" }
        }
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
                if (image != null) bodyBuilder.addFormDataPart(
                    "image", "foto.jpg", image.asRequestBody("image/jpeg".toMediaType())
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

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(statusRunnable)
        releaseRecorder()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
