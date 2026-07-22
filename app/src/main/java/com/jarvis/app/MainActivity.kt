package com.jarvis.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Etappe 2, Schicht 0 ("walking skeleton"): die duennste durchgehende Linie
 * Handy -> Jarvis-Orchestrator -> Antwort. Bewusst nur Text, damit dieser
 * erste Build beweist, dass (a) der Cloud-Build eine installierbare App
 * erzeugt, (b) sich die App oeffnet und (c) das Handy den /assistant-Endpunkt
 * ueber die ngrok-Domain erreicht. Sprachaufnahme und Kamera kommen als
 * naechste Schichten obendrauf.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlField = findViewById<EditText>(R.id.serverUrl)
        val keyField = findViewById<EditText>(R.id.accessKey)
        val textField = findViewById<EditText>(R.id.messageText)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val answerView = findViewById<TextView>(R.id.answerView)

        // URL und Schluessel werden gespeichert, damit Doreen sie nur EINMAL
        // eintippen muss - beim naechsten Start sind sie schon da.
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        urlField.setText(prefs.getString("url", ""))
        keyField.setText(prefs.getString("key", ""))

        sendButton.setOnClickListener {
            val base = urlField.text.toString().trim().trimEnd('/')
            val key = keyField.text.toString().trim()
            val msg = textField.text.toString().trim()
            if (base.isEmpty() || key.isEmpty() || msg.isEmpty()) {
                answerView.text = "Bitte Server-URL, Schluessel und Nachricht ausfuellen."
                return@setOnClickListener
            }
            // Fuer den naechsten Start merken.
            prefs.edit().putString("url", base).putString("key", key).apply()
            answerView.text = "Sende …"
            // Netzwerk niemals im UI-Thread - sonst friert die App ein.
            thread {
                val result = sendMessage(base, key, msg)
                runOnUiThread { answerView.text = result }
            }
        }
    }

    /**
     * Schickt die Nachricht als einfaches Formular (urlencoded) an
     * /assistant. Der Endpunkt akzeptiert per FastAPI-Form sowohl
     * urlencoded als auch multipart - fuer reinen Text reicht urlencoded,
     * das spart hier jede externe Bibliothek. Rueckgabe ist das Antwort-
     * feld aus dem JSON, bei Fehlern eine lesbare Meldung.
     */
    private fun sendMessage(base: String, key: String, text: String): String {
        return try {
            val conn = URL("$base/assistant").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // ngrok blendet Nicht-Browser-Clients gegenueber sonst eine
            // HTML-Warnseite ein - dieser Header ueberspringt sie.
            conn.setRequestProperty("ngrok-skip-browser-warning", "true")

            val body = "key=" + URLEncoder.encode(key, "UTF-8") +
                "&text=" + URLEncoder.encode(text, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299) {
                JSONObject(response).optString("text", response)
            } else {
                "Fehler $code: $response"
            }
        } catch (e: Exception) {
            "Verbindungsfehler: ${e.message}"
        }
    }
}
