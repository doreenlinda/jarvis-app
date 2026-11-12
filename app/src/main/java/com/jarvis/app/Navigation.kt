package com.jarvis.app

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

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
        // GESPERRTES HANDY (v0.63, Livefall 12.11.2026): Android laesst eine
        // App bei gesperrtem Bildschirm keine andere oeffnen - und meldet
        // das NICHT, startActivity kehrt ohne Fehler zurueck. Maps blieb zu,
        // sie griff zum Kartenlink im Postfach. Darum zusaetzlich eine
        // Mitteilung auf dem Sperrbildschirm: antippen, entsperren, Maps
        // startet die Zielfuehrung. Der direkte Versuch unten laeuft
        // trotzdem - laesst ein Geraet ihn durch, ist Maps schon da.
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

    private const val KANAL = "jarvis_navigation"
    private const val MITTEILUNG_ID = 7301

    /** Ist der Sperrbildschirm aktiv? Im Zweifel: nein (dann wie bisher). */
    private fun gesperrt(ctx: Context): Boolean =
        try {
            (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .isKeyguardLocked
        } catch (_: Throwable) {
            false
        }

    /**
     * Mitteilung, deren Antippen DIREKT die Zielfuehrung startet - kein
     * Umweg ueber die Jarvis-App.
     *
     * Nur ein Kanal mit HOHER Wichtigkeit erscheint als Kopfzeile und auf
     * dem Sperrbildschirm.
     *
     * AUF DEM SPERRBILDSCHIRM KEIN NAME: Wie bei allen Jarvis-Meldungen
     * (VISIBILITY_PRIVATE) steht dort nur "Navigation bereit". Der Name
     * einer Kundin gehoert nicht auf ein Display, das jeder sieht; nach
     * dem Entsperren steht das Ziel da.
     *
     * Nach 30 Minuten verschwindet sie von selbst - eine alte Route soll
     * am Nachmittag nicht mehr herumliegen.
     */
    private fun alsMitteilung(ctx: Context, ziel: String, uri: Uri) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAL, "Navigation", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val maps = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(MAPS)
            val antippen = PendingIntent.getActivity(
                ctx, MITTEILUNG_ID, maps,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val oeffentlich = NotificationCompat.Builder(ctx, KANAL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Navigation bereit")
                .setContentText("Antippen und entsperren – Maps startet")
                .build()
            val mitteilung = NotificationCompat.Builder(ctx, KANAL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Navigation zu $ziel")
                .setContentText("Antippen – Maps startet die Zielführung")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(oeffentlich)
                .setContentIntent(antippen)
                .setAutoCancel(true)
                .setTimeoutAfter(30L * 60 * 1000)
                .build()
            nm.notify(MITTEILUNG_ID, mitteilung)
            Log.i("Jarvis", "Navigation als Mitteilung (gesperrt): $ziel")
        } catch (t: Throwable) {
            // Ohne Mitteilung bleibt der direkte Versuch - und der
            // Kartenlink im Postfach.
            meldeFehler(ctx, "FEHLER: Navigations-Mitteilung zu $ziel (${t.javaClass.simpleName})")
        }
    }

    private fun meldeFehler(ctx: Context, text: String) {
        try {
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                .edit().putString("wake_status", text).apply()
        } catch (_: Throwable) {
            // Selbst die Meldung darf den Strom nicht mitreissen.
        }
    }
}
