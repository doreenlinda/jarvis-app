package com.jarvis.app

import android.content.Context
import android.media.AudioManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * MESSUNG (v0.58): Wie laut kam ihre Frage am Mikrofon an?
 *
 * WOZU
 * ----
 * Doreen meldete am 17.09.2026 aus dem Auto: In der App stand "Keine Frage
 * gehoert - ich lausche weiter", obwohl sie gesprochen hatte. Beim Server
 * kam nichts an, und das ist kein Zufall: `aufnehmenAusStrom` sendet bei
 * `if (!gesprochen) return null` GAR NICHTS, wenn kein einziger 80-ms-Block
 * ueber SPRACH_PEGEL liegt.
 *
 * WARUM DIE VORHANDENE ANZEIGE NICHT REICHT
 * -----------------------------------------
 * "Mikro-Pegel" in der Statuszeile laeuft AUSSCHLIESSLICH in der
 * Lausch-Schleife. Ist das Weckwort erkannt, verlaesst der Code sie, und die
 * Aufnahme der Frage laeuft an anderer Stelle - dort wird nichts angezeigt.
 *
 * Genau das hat sie am 18.09. selbst beobachtet ("beim Sprechen war der
 * Pegel nicht sichtbar"). Ihr abgelesener Wert stammt also aus der Zeit VOR
 * oder NACH der Frage.
 *
 * Dazu kommt ihr eigener Einwand: Beim FAHREN kann sie ohnehin nichts
 * ablesen. Deshalb geht die Zahl zum Server statt auf den Schirm.
 *
 * WAS SIE TRENNT
 * --------------
 * Zwei Erklaerungen sind moeglich, und sie verlangen entgegengesetzte
 * Antworten:
 *
 *   Pegel zweistellig      -> das Mikrofon liefert nichts. Eine niedrigere
 *                             Schwelle wuerde daran NICHTS aendern.
 *   Pegel knapp unter 1500 -> die Schwelle ist fuer die Situation zu hoch.
 *                             Dann - und nur dann - ist sie der Hebel.
 *
 * WAS HIER PASSIERT: NICHTS AUSSER MESSEN
 * ---------------------------------------
 * An der Aufnahme wird NICHTS veraendert - kein Pegel, keine Schwelle, kein
 * Abbruchverhalten. Gezaehlt wird nur, was ohnehin schon berechnet wird.
 * Das ist Absicht: SPRACH_PEGEL ist die Sicherung gegen aufgeschnappte
 * Raumgespraeche (neun Fehlausloesungen am 26.07.2026), und am Audio-Weg
 * hat eine Aenderung auf Verdacht schon einmal alles gekostet (v0.49).
 *
 * AUCH DIE GELUNGENEN ZURUFE WERDEN GEMELDET
 * ------------------------------------------
 * Ohne den Nenner ist die Zahl der Fehlschlaege wertlos - dieselbe Lektion
 * wie bei der Zurufs-Messung. Der Aufruf laeuft dafuer in einem eigenen
 * Thread: Ein gelungener Zuruf darf durch die Messung keine Sekunde
 * langsamer werden.
 *
 * UEBERTRAGEN WERDEN NUR ZAHLEN - kein Ton, kein Text.
 */
object MikroProbe {

    private const val PREFS = "jarvis"

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Meldet EINE Aufnahme.
     *
     * @param anlass     "weckwort" oder "nachfass" - beim Nachfass-Fenster
     *                   ist ein Fehlschlag normal (es stand nur offen),
     *                   beim Weckwort nicht. Ohne diese Unterscheidung
     *                   waere die Rate nicht deutbar.
     * @param gesprochen ob ueberhaupt etwas gesendet wurde
     * @param maxPegel   hoechster Blockwert der Aufnahme
     * @param schnitt    Durchschnitt ueber alle Bloecke (trennt
     *                   Umgebungsgeraeusch von einzelnen Ausschlaegen)
     * @param bloecke    Anzahl ausgewerteter Bloecke
     * @param graubereich  Bloecke ueber der halben Schwelle, aber darunter -
     *                   "da war etwas, nur zu leise"
     */
    /**
     * WER HAT DAS MIKROFON (ab v0.59)
     *
     * @param quelle  Der Typ des Eingabegeraets, ueber das WIRKLICH
     *   aufgenommen wurde (AudioDeviceInfo.TYPE_*), oder -1.
     *
     *   DER ZEITPUNKT IST HIER ALLES: `getRoutedDevice()` liefert nur
     *   waehrend der laufenden Aufnahme einen Wert. Deshalb wird er aus der
     *   Aufnahmeschleife hereingereicht und NICHT hier geholt - genau
     *   dieser Fehler hat die Ton-Umleitung aus v0.57 wirkungslos gemacht
     *   (sie fragte VOR dem Start und merkte nie, dass sie umleiten muss).
     */
    fun melde(
        ctx: Context, anlass: String, gesprochen: Boolean, maxPegel: Int,
        schnitt: Int, bloecke: Int, graubereich: Int, schwelle: Int,
        dauerMs: Long, quelle: Int = -1,
    ) {
        thread {
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
                val key = prefs.getString("key", "") ?: ""
                if (basis.isEmpty() || key.isEmpty()) return@thread
                // Die vorhandenen EINGAENGE und der Audiomodus kommen erst
                // hier dazu - sie aendern sich waehrend einer Aufnahme
                // nicht, und der Aufruf gehoert nicht in die Schleife.
                var vorhanden = ""
                var modus = ""
                try {
                    val manager = ctx.applicationContext
                        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    vorhanden = manager
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .map { it.type }.distinct().joinToString(",")
                    // Steht hier IM ANRUF, hat die Telefonie das Mikrofon -
                    // der direkte Beleg fuer die Freisprecheinrichtung.
                    modus = manager.mode.toString()
                } catch (_: Throwable) {
                    // Dann fehlen die zwei Felder eben; der Pegel ist
                    // wichtiger als sie.
                }
                val felder = FormBody.Builder()
                    .add("key", key)
                    .add("anlass", anlass)
                    .add("gesprochen", if (gesprochen) "1" else "0")
                    .add("max_pegel", maxPegel.toString())
                    .add("schnitt_pegel", schnitt.toString())
                    .add("bloecke", bloecke.toString())
                    .add("graubereich", graubereich.toString())
                    // Die eigene Schwelle kommt mit: Wird sie hier spaeter
                    // verstellt, deutet das Protokoll sonst still falsch.
                    .add("schwelle", schwelle.toString())
                    .add("dauer_ms", dauerMs.toString())
                    // Leere Felder statt "-1": Der Server soll "nicht
                    // gemeldet" von "unbekanntes Geraet" unterscheiden
                    // koennen, sonst steht in der Zeile eine erfundene
                    // Angabe.
                    .add("quelle", if (quelle >= 0) quelle.toString() else "")
                    .add("verfuegbar", vorhanden)
                    .add("modus", modus)
                    .build()
                client.newCall(
                    Request.Builder()
                        .url("$basis/mikro-diagnose")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(felder)
                        .build()
                ).execute().close()
            } catch (_: Throwable) {
                // Schlaegt die Messung fehl, ist das folgenlos - sie ist
                // eine Beobachtung, kein Bestandteil der Antwort.
            }
        }
    }
}
