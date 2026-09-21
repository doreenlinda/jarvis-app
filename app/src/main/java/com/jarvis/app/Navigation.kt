package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Zielfuehrung starten (v0.60, 21.09.2026).
 *
 * Der Server kann nicht navigieren - er kann nur bitten. Google Maps
 * oeffnet sich auf ihrem Handy, und der Intent dafuer laesst sich nur hier
 * ausloesen. Der Befehl kommt ueber den offenen NDJSON-Strom, genau wie
 * der WhatsApp-Versand seit v0.38.
 *
 * KEIN KONTO, KEIN SCHLUESSEL: "google.navigation:q=..." ist ein
 * Android-Intent. Maps liegt ohnehin auf ihrem Handy.
 *
 * WAS IM STRING STEHT, entscheidet der Server: Bei einer hinterlegten
 * Ortszone ist es deren geocodierte ADRESSE (lesbar - sie sieht in Maps,
 * wohin es geht, bevor sie losfaehrt), sonst ihre woertliche Angabe, die
 * Maps dann selbst sucht.
 */
object Navigation {

    private const val MAPS = "com.google.android.apps.maps"

    /**
     * Startet die Zielfuehrung. Gibt zurueck, ob ein Handler gefunden wurde.
     *
     * ZWEI VERSUCHE, und die Reihenfolge ist Absicht: erst gezielt Google
     * Maps, dann ohne Paketangabe. Mit Paket geht Maps ohne Nachfrage auf -
     * im Auto zaehlt jeder Tipp, den sie nicht machen muss. Fehlt Maps oder
     * ist es deaktiviert, faengt der zweite Versuch es ab; dann darf das
     * System fragen, wer navigieren soll. Umgekehrt waere es schlechter:
     * Ein Auswahldialog bei jeder Fahrt ist genau die Zumutung, die der
     * Zuruf ersparen soll.
     *
     * FLAG_ACTIVITY_NEW_TASK ist Pflicht, nicht Kosmetik: Der Aufruf kommt
     * aus dem Weckwort-DIENST, und ein Dienst hat keine Activity, von der
     * aus sich eine neue oeffnen koennte - ohne das Flag wirft Android.
     */
    fun starten(ctx: Context, ziel: String, abfrage: String): Boolean {
        val uri = Uri.parse(zielUri(abfrage))
        var letzter = ""
        for (paket in listOf(MAPS, "")) {
            try {
                val i = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (paket.isNotEmpty()) i.setPackage(paket)
                ctx.startActivity(i)
                Log.i("Jarvis", "Navigation gestartet: $ziel")
                return true
            } catch (e: Throwable) {
                letzter = e.javaClass.simpleName
            }
        }
        // NICHT STUMM SCHEITERN. Sie sieht zwar sofort, dass Maps nicht
        // aufgeht - warum, stuende aber nirgends. Genau daran scheiterte
        // die Weckwort-Fehlersuche bis v0.7: Auf dem Handy gibt es kein
        // lesbares Log, also muss ein Fehler in die Statuszeile.
        meldeFehler(ctx, "FEHLER: Navigation zu $ziel nicht moeglich ($letzter)")
        Log.w("Jarvis", "Navigation zu $ziel fehlgeschlagen: $letzter")
        return false
    }

    /**
     * Die Intent-Adresse fuer ein Ziel.
     *
     * Uri.encode ist Pflicht: In "Okerstrasse 39, 12049 Berlin" stecken
     * Leerzeichen und ein Komma, und ihre eigene Adresse traegt einen
     * Umlaut ("Am Roetepfuhl" steht als "Am Rötepfuhl" in der Zone).
     * Ungekodiert bricht der Intent oder landet am falschen Ort.
     */
    fun zielUri(abfrage: String): String =
        "google.navigation:q=" + Uri.encode(abfrage)

    private fun meldeFehler(ctx: Context, text: String) {
        try {
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                .edit().putString("wake_status", text).apply()
        } catch (_: Throwable) {
            // Selbst die Meldung darf den Strom nicht mitreissen.
        }
    }
}
