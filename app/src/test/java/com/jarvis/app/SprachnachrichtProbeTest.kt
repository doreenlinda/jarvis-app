package com.jarvis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft die Erkennung des Sprachnachrichten-Platzhalters (v0.38).
 *
 * Warum hier: Der Laptop hat keine Java-Werkzeugkette - Kotlin laeuft
 * ausschliesslich im Cloud-Build. Dieser Test liegt deshalb, wie
 * KryptoFormatTest, StandortFormatTest und WhatsAppFilterTest, VOR dem Bauen
 * im Workflow.
 *
 * Was auf dem Spiel steht: Erkennt die Regel nichts, laeuft die MESSUNG nie
 * und die Frage "kommt die App an eine Sprachnachricht heran?" bliebe offen -
 * genau die Frage, fuer die diese Fassung gebaut ist. Ein Fehlgriff in die
 * andere Richtung ist dagegen folgenlos: Die Probe sieht sich nur einen
 * Ordner an, liest keine Datei und uebertraegt keinen Ton.
 */
class SprachnachrichtProbeTest {

    @Test
    fun deutscherPlatzhalterWirdErkannt() {
        assertTrue(SprachnachrichtProbe.istSprachnachricht("🎤 Sprachnachricht (0:29)"))
        assertTrue(SprachnachrichtProbe.istSprachnachricht("Sprachnachricht"))
    }

    @Test
    fun englischerPlatzhalterWirdErkannt() {
        assertTrue(SprachnachrichtProbe.istSprachnachricht("🎤 Voice message (0:12)"))
    }

    @Test
    fun auchNurDasMikrofonzeichen() {
        // Je nach Systemsprache steht dort teils nur das Symbol.
        assertTrue(SprachnachrichtProbe.istSprachnachricht("🎤 0:07"))
    }

    @Test
    fun grossKleinschreibungEgal() {
        assertTrue(SprachnachrichtProbe.istSprachnachricht("SPRACHNACHRICHT (1:04)"))
    }

    @Test
    fun normaleNachrichtenLoesenNichtAus() {
        // Diese Saetze duerfen die Messung NICHT anstossen - sonst liefe sie
        // bei jeder zweiten Nachricht (folgenlos, aber unnoetig).
        assertFalse(SprachnachrichtProbe.istSprachnachricht("Ich muss morgen leider absagen."))
        assertFalse(SprachnachrichtProbe.istSprachnachricht("Passt Donnerstag um 15 Uhr?"))
        assertFalse(SprachnachrichtProbe.istSprachnachricht("Bin unterwegs, bis gleich"))
        assertFalse(SprachnachrichtProbe.istSprachnachricht(""))
    }
}
