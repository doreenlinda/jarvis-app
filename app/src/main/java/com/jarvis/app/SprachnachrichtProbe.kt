package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.concurrent.thread

/**
 * MESSUNG (v0.38): Kommt die App an WhatsApps Sprachnachrichten heran?
 *
 * WOZU
 * ----
 * 33 % ihrer WhatsApp-Nachrichten sind Sprachnachrichten und fuer Jarvis
 * heute blind - aus der Benachrichtigung kommt nur der Platzhalter
 * "Sprachnachricht (0:29)", mehr gibt WhatsApp dort nicht mit. Transkribieren
 * koennte sie dasselbe lokale Whisper, das ihre Zurufe versteht: kostenlos,
 * ohne weiteren Anbieter, ohne dass Ton das Haus verlaesst.
 *
 * Dazwischen steht genau EINE Frage, die vom Laptop aus nicht zu beantworten
 * ist: Darf diese App die Datei ueberhaupt lesen? Android hat den Zugriff auf
 * fremde App-Ordner seit Version 11 schrittweise zugemacht, und WhatsApps
 * Medienordner traegt eine .nomedia-Datei, die ihn aus dem Medienspeicher
 * heraushaelt. Ob der Dateiweg, der Medienspeicher oder keiner von beiden
 * traegt, entscheidet sich auf ihrem Geraet - also wird es dort gemessen,
 * nach demselben Muster wie die Antwort-Messung davor.
 *
 * WAS HIER PASSIERT: NICHTS AUSSER MESSEN
 * ---------------------------------------
 * Es wird KEINE Datei gelesen, KEIN Ton uebertragen und nichts
 * transkribiert. Gemessen wird, ob der Ordner sichtbar ist und wie viele
 * Dateien darin liegen. Was an den Server geht, sind ein Ergebniswort und
 * eine technische Notiz - kein Inhalt, kein Absender, keine Dateinamen.
 * Der naechste Schritt braucht erst ihre Entscheidung: Franks gesprochenes
 * Wort laege danach als durchsuchbarer Text in ihrer Datenbank.
 */
object SprachnachrichtProbe {

    private val SPRACHNACHRICHT = Regex(
        """(sprachnachricht|voice message|audio|🎤|🎙)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Sieht dieser Benachrichtigungstext nach einer Sprachnachricht aus?
     *
     * Bewusst grosszuegig, und ein Fehlgriff ist hier folgenlos: Die Probe
     * sieht sich nur einen ORDNER an - ob gerade wirklich eine
     * Sprachnachricht kam, aendert am Ergebnis nichts. Zu ENG waere dagegen
     * teuer: Dann liefe die Messung nie, und die Frage bliebe offen. Die
     * genaue Schreibweise haengt ausserdem an der Systemsprache.
     *
     * Oeffentlich, damit sie im Cloud-Build geprueft werden kann (der Laptop
     * hat keine Java-Werkzeugkette) - dasselbe Muster wie bei Krypto,
     * Standort und WhatsAppFilter.
     */
    fun istSprachnachricht(text: String): Boolean =
        SPRACHNACHRICHT.containsMatchIn(text)

    /** Hoechstens alle sechs Stunden messen. Einmal pro Prozessstart waere zu
     *  wenig: Erteilt sie die Berechtigung spaeter, soll die Messung sie noch
     *  mitbekommen - ohne dass bei jeder Sprachnachricht eine Zeile anfaellt. */
    private const val ABSTAND_MS = 6L * 60 * 60 * 1000
    private const val PREFS = "jarvis"
    private const val ZULETZT = "sprachprobe_zuletzt"

    /** Wo WhatsApp Sprachnachrichten ablegt. Der erste Pfad gilt seit
     *  Android 11, der zweite ist der alte Ort, der dritte WhatsApp Business. */
    private val PFADE = listOf(
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
        "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes",
        "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
    )

    private val client = OkHttpClient()

    fun vielleichtMessen(ctx: Context, text: String) {
        if (!istSprachnachricht(text)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zuletzt = prefs.getLong(ZULETZT, 0L)
        val jetzt = System.currentTimeMillis()
        if (jetzt - zuletzt < ABSTAND_MS) return
        prefs.edit().putLong(ZULETZT, jetzt).apply()
        thread {
            try {
                val (ergebnis, detail) = messen(ctx)
                melden(ctx, ergebnis, detail)
            } catch (_: Throwable) {
                // Eine verlorene Messzeile ist folgenlos - eine Messung darf
                // das Mitlesen unter keinen Umstaenden stoeren.
            }
        }
    }

    /** Die eigentliche Probe. Rueckgabe: (Ergebniswort, technische Notiz). */
    fun messen(ctx: Context): Pair<String, String> {
        val notizen = StringBuilder()
        notizen.append("berechtigung=").append(if (darfLesen(ctx)) "ja" else "nein")

        var bestes = "nicht_gefunden"
        for (pfad in PFADE) {
            val ordner = File(pfad)
            val da = try { ordner.exists() } catch (_: Throwable) { false }
            if (!da) continue
            val lesbar = try { ordner.canRead() } catch (_: Throwable) { false }
            // listFiles() liefert null, wenn der Zugriff verwehrt wird - das
            // ist die eigentliche Antwort, nicht canRead().
            val inhalt = try { ordner.listFiles() } catch (_: Throwable) { null }
            if (inhalt == null) {
                notizen.append("; ").append(kurz(pfad)).append(": da, aber nicht lesbar")
                if (bestes == "nicht_gefunden") bestes = "nicht_lesbar"
                continue
            }
            // Eine Ebene tiefer schauen: WhatsApp legt Monatsordner an.
            var dateien = 0
            for (eintrag in inhalt) {
                if (eintrag.isDirectory) {
                    dateien += try { eintrag.listFiles()?.size ?: 0 } catch (_: Throwable) { 0 }
                } else {
                    dateien++
                }
            }
            notizen.append("; ").append(kurz(pfad))
                .append(": lesbar=").append(lesbar)
                .append(", eintraege=").append(inhalt.size)
                .append(", dateien=").append(dateien)
            bestes = if (dateien > 0) "lesbar" else "lesbar_leer"
            break
        }
        if (bestes == "nicht_gefunden") notizen.append("; kein Ordner gefunden")

        notizen.append("; ").append(medienspeicher(ctx))
        return bestes to notizen.toString()
    }

    /** Zweiter moeglicher Weg: Androids Medienspeicher. Wegen der
     *  .nomedia-Datei in WhatsApps Ordner erwartungsgemaess leer - aber genau
     *  das gehoert gemessen statt vermutet, sonst faellt der Weg unter den
     *  Tisch, falls er doch traegt. */
    private fun medienspeicher(ctx: Context): String = try {
        val spalten = arrayOf(MediaStore.Audio.Media._ID)
        val wo = MediaStore.Audio.Media.DATA + " LIKE ?"
        val wert = arrayOf("%WhatsApp%Voice Notes%")
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, spalten, wo, wert, null
        ).use { c ->
            "medienspeicher=" + (c?.count ?: -1)
        }
    } catch (e: Throwable) {
        "medienspeicher=Fehler(" + e.javaClass.simpleName + ")"
    }

    private fun darfLesen(ctx: Context): Boolean {
        val recht = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ctx.checkSelfPermission(recht) == PackageManager.PERMISSION_GRANTED
    }

    /** Nur der markante Teil des Pfades - die Zeile soll lesbar bleiben. */
    private fun kurz(pfad: String): String = when {
        pfad.contains("Android/media/com.whatsapp/") -> "neuer Ort"
        pfad.contains("w4b") -> "Business"
        else -> "alter Ort"
    }

    private fun melden(ctx: Context, ergebnis: String, detail: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return
        client.newCall(
            Request.Builder()
                .url("$basis/whatsapp-sprachnachricht-messung")
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(
                    FormBody.Builder()
                        .add("key", key)
                        .add("ergebnis", ergebnis)
                        .add("detail", detail.take(400))
                        .build()
                )
                .build()
        ).execute().close()
    }
}
