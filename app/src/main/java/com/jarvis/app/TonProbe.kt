package com.jarvis.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

/**
 * MESSUNG (v0.56): Wird Jarvis' Stimme wirklich abgespielt - und wohin?
 *
 * WOZU
 * ----
 * Doreen bat am 16.09.2026 im Auto darum, Jessica eine Verspaetung zu
 * melden. Auf dem Schirm stand "Antwort laeuft" ueber Minuten, gehoert hat
 * sie NICHTS - weder ueber die Freisprecheinrichtung noch ueber das Handy.
 *
 * Serverseitig ist alles belegt: Beide Sprachbloecke wurden erzeugt und
 * gesendet (09:48:35 und 09:48:39, kein Abbruch), und die App hat 14
 * Sekunden spaeter von selbst eine neue Anfrage geschickt. Der Verlust
 * liegt also HIER, bei der Wiedergabe - und die ist vom Laptop aus nicht
 * einsehbar.
 *
 * DER GRUND, WARUM MAN BISHER NICHTS SIEHT
 * ----------------------------------------
 * Von vier Abspielstellen in dieser App hat GENAU EINE einen
 * Fehler-Rueckkanal (Rueckfrage.kt). Die beiden Wege, ueber die ihre
 * Antworten laufen, verschlucken jeden Fehler stumm:
 *
 *   StreamClient.AudioQueue        catch (e: Exception) { delete(); weiter }
 *   WakeWordService.spieleAntwort  catch (_: Exception) { }
 *
 * Damit sind zwei Fehlerbilder unsichtbar, und beide erklaeren ihren Fall:
 *
 *   1. prepare()/start() wirft -> der Block wird geloescht und
 *      uebersprungen, die Antwort laeuft LAUTLOS durch. Passt dazu, dass
 *      die App 14 Sekunden spaeter wieder aufnahmebereit war.
 *   2. MediaPlayer scheitert ASYNCHRON nach start() -> ohne
 *      OnErrorListener kommt kein onCompletion, die Warteschlange bleibt
 *      stehen und awaitEnde() laeuft in seine 180 Sekunden. Passt zu
 *      "Antwort laeuft ueber Minuten".
 *
 * Welches von beiden es war, entscheidet EINE Zahl - und die gibt es bis
 * heute nirgends. Das ist dieselbe Lektion wie bei v0.7: Auf dem Handy
 * gibt es kein lesbares Log, App-Fehler duerfen nie stumm verschluckt
 * werden.
 *
 * WAS HIER PASSIERT: NICHTS AUSSER MESSEN
 * ---------------------------------------
 * Am Audio-Weg wird NICHTS veraendert. Der hier gesetzte OnErrorListener
 * gibt `false` zurueck und ist damit verhaltensneutral: Android ruft dann
 * denselben OnCompletionListener auf wie ohne Listener (dokumentiert bei
 * MediaPlayer.OnErrorListener). Er protokolliert nur, was ohnehin passiert
 * waere. Das ist Absicht - an diesem Weg haben v0.49 und v0.50 schon
 * einmal etwas verschlimmert, v0.49 kostete den Ton ueber Bluetooth
 * komplett.
 *
 * An den Server gehen ausschliesslich Zahlen, ein Geraetetyp und
 * hoechstens der Text einer Fehlermeldung des Abspielers. KEIN Ton, KEIN
 * Antworttext - es gibt hier also auch nichts zu verschluesseln.
 */
object TonProbe {

    const val QUELLE_STROM = "strom"
    const val QUELLE_WECKWORT = "weckwort"
    const val QUELLE_KNOPF = "knopf"

    private const val PREFS = "jarvis"
    private val client = OkHttpClient()

    /**
     * Welcher Ausgang zaehlt, wenn mehrere verfuegbar sind - REINE
     * Funktion, damit der Cloud-Build sie wirklich ausfuehren kann
     * (Kotlin laesst sich auf dem Laptop nicht testen).
     *
     * Das ist nur der RUECKFALL. Der verlaessliche Weg ist
     * MediaPlayer.getRoutedDevice(): Der sagt, wohin dieser eine Player
     * tatsaechlich spielt, statt es aus der Liste der vorhandenen Geraete
     * zu erraten. Gefragt wird die Liste erst, wenn der Player ihn nicht
     * hergibt.
     *
     * Die Reihenfolge bildet ab, was Android bei gleichzeitig vorhandenen
     * Ausgaengen nimmt: ein angeschlossenes Geraet schlaegt den eingebauten
     * Lautsprecher. Bluetooth steht vorn, weil das der Weg ins Auto ist -
     * und um den geht es hier.
     */
    fun massgeblicherAusgang(typen: IntArray): Int {
        if (typen.isEmpty()) return -1
        val rang = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        for (t in rang) if (typen.contains(t)) return t
        return typen[0]
    }

    /**
     * Sammelt die Zahlen EINER Antwort und meldet sie EINMAL am Ende.
     *
     * Bewusst je Antwort und nicht je Block: Eine satzweise Antwort hat
     * schnell fuenf Bloecke, und fuenf Meldungen pro Zuruf waeren Rauschen
     * in einer Datei, die eine offene Frage beantworten soll.
     */
    class Lauf(private val quelle: String) {
        private var bloecke = 0
        private var gespielt = 0
        private var fehler = 0
        private var ersterFehler = ""
        private var ausgang = -1
        private val beginn = System.currentTimeMillis()
        private var gemeldet = false

        @Synchronized
        fun uebergeben() {
            bloecke++
        }

        @Synchronized
        fun gespielt(mp: MediaPlayer?) {
            gespielt++
            if (ausgang < 0) ausgang = routedDevice(mp)
        }

        @Synchronized
        fun gescheitert(phase: String, text: String) {
            fehler++
            if (ersterFehler.isEmpty()) {
                ersterFehler = "$phase: ${text.take(160)}"
            }
        }

        /** Meldet im Hintergrund - eine Messung darf die Antwort nie
         *  aufhalten, waehrend Doreen schon die naechste Frage stellt. */
        @Synchronized
        fun melden(ctx: Context) {
            if (gemeldet) return
            gemeldet = true
            val dauer = System.currentTimeMillis() - beginn
            val b = bloecke
            val g = gespielt
            val f = fehler
            val text = ersterFehler
            var typ = ausgang
            thread {
                try {
                    val manager = ctx.applicationContext
                        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (typ < 0) {
                        val vorhanden = manager
                            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .map { it.type }.toIntArray()
                        typ = massgeblicherAusgang(vorhanden)
                    }
                    val laut = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxLaut = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    senden(ctx, quelle, b, g, f, text, typ, laut, maxLaut, dauer)
                } catch (_: Throwable) {
                    // Schlaegt die Messung fehl, ist das folgenlos - sie
                    // ist eine Beobachtung, kein Bestandteil der Antwort.
                }
            }
        }
    }

    /** Wohin dieser Player wirklich spielt - das schlaegt jede Vermutung. */
    private fun routedDevice(mp: MediaPlayer?): Int = try {
        mp?.routedDevice?.type ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun senden(
        ctx: Context, quelle: String, bloecke: Int, gespielt: Int,
        fehler: Int, fehlertext: String, ausgang: Int, laut: Int,
        maxLaut: Int, dauerMs: Long,
    ) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return
        val felder = FormBody.Builder()
            .add("key", key)
            .add("quelle", quelle)
                .add("transcript", "was sie gesagt hat")
            .add("bloecke", bloecke.toString())
            .add("gespielt", gespielt.toString())
            .add("fehler", fehler.toString())
            .add("fehlertext", fehlertext.take(200))
            .add("ausgang", if (ausgang >= 0) ausgang.toString() else "")
            .add("lautstaerke", laut.toString())
            .add("max_lautstaerke", maxLaut.toString())
            .add("dauer_ms", dauerMs.toString())
            .build()
        client.newCall(
            Request.Builder()
                .url("$basis/ton-diagnose")
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(felder)
                .build()
        ).execute().close()
    }
}
