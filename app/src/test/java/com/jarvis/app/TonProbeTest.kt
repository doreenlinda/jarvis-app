package com.jarvis.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Ton-Messung: Wird Jarvis' Stimme wirklich abgespielt - und wohin?
 *
 * ANLASS (16.09.2026): Doreen bat im Auto darum, Jessica eine Verspaetung
 * zu melden. Auf dem Schirm stand "Antwort laeuft" ueber Minuten, gehoert
 * hat sie nichts. Serverseitig ist alles belegt - der Verlust liegt bei
 * der Wiedergabe, und die war bis heute unsichtbar: Von vier
 * Abspielstellen hat genau EINE einen Fehler-Rueckkanal.
 *
 * WARUM ES DIESEN TEST GIBT - vier Gefahren, die im Quelltext beim
 * Drueberlesen nicht auffallen:
 *
 *   1. DER MERKER. Ein asynchron gescheiterter MediaPlayer loest trotzdem
 *      onCompletion aus. Ohne `fehlgeschlagen` zaehlte er als GESPIELT -
 *      die Messung wuerde dann "teilweise abgespielt" melden, wo nichts zu
 *      hoeren war. Eine Messung, die das Gegenteil der Wahrheit sagt, ist
 *      schlimmer als keine.
 *   2. DAS `false` IM FEHLER-LISTENER. Nur damit ist der Listener
 *      verhaltensneutral: Android ruft danach denselben
 *      OnCompletionListener auf wie ohne Listener. Ein `true` waere eine
 *      echte Verhaltensaenderung - die Warteschlange bliebe stehen. Am
 *      Audio-Weg soll NICHTS veraendert werden, bevor gemessen wurde;
 *      dort haben v0.49 und v0.50 schon einmal etwas verschlimmert.
 *   3. BEIDE WEGE. Die Antworten laufen ueber zwei Abspielstellen. Haengt
 *      die Messung nur an einer, ist die Auswertung ein Zufallsbefund.
 *   4. KEINE INHALTE. Es gehen Zahlen und ein Geraetetyp hinaus - kein
 *      Ton, kein Antworttext. Sonst waere die Messung eine zweite,
 *      unverschluesselte Datenspur.
 *
 * EHRLICHE GRENZE: Nur `massgeblicherAusgang` wird wirklich AUSGEFUEHRT.
 * Der Rest liest Quelltext - MediaPlayer und AudioManager gibt es im
 * Unit-Test nicht. Der Test belegt also, dass die Angaben dastehen, nicht
 * wie es am Geraet klingt.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class TonProbeTest {

    private fun lies(name: String): String {
        val datei = File("src/main/java/com/jarvis/app/$name")
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText()
    }

    // --- Die einzige wirklich ausfuehrbare Haelfte ----------------------

    @Test
    fun bluetoothSchlaegtDenGeraetelautsprecher() {
        // Der Fall, um den es geht: Im Auto haengt das Handy per Bluetooth.
        // Waere hier der Lautsprecher das Ergebnis, zeigte die Messung auf
        // den falschen Ausgang - und die Fehlersuche liefe in die Irre.
        val typen = intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TonProbe.massgeblicherAusgang(typen)
        )
    }

    @Test
    fun kopfhoererSchlagenDenGeraetelautsprecher() {
        val typen = intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            TonProbe.massgeblicherAusgang(typen)
        )
    }

    @Test
    fun ohneAngeschlossenesGeraetBleibtDerLautsprecher() {
        val typen = intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            TonProbe.massgeblicherAusgang(typen)
        )
    }

    @Test
    fun ohneGeraeteWirdNichtsGeraten() {
        // -1 heisst "unbekannt", und der Server weist es als unbekannt aus.
        // Ein geratener Ausgang waere schlimmer als ein fehlender.
        assertEquals(-1, TonProbe.massgeblicherAusgang(intArrayOf()))
    }

    @Test
    fun einUnbekannterTypGehtDurchStattVerworfenZuWerden() {
        // Ein Geraetetyp, den diese Reihenfolge nicht kennt, darf nicht
        // stillschweigend zu "unbekannt" werden - der Server kann ihn als
        // Zahl ausweisen, und das ist eine echte Information.
        assertEquals(99, TonProbe.massgeblicherAusgang(intArrayOf(99)))
    }

    // --- Die drei Gefahren im Quelltext --------------------------------

    @Test
    fun einGescheiterterBlockZaehltNichtAlsGespielt() {
        for (name in listOf("StreamClient.kt", "WakeWordService.kt")) {
            val q = lies(name)
            assertTrue(
                "$name: Merker fehlt - ein asynchron gescheiterter Player " +
                    "wuerde als gespielt gezaehlt",
                q.contains("var fehlgeschlagen = false")
            )
            assertTrue(
                "$name: onCompletion prueft den Merker nicht",
                q.contains("if (!fehlgeschlagen) probe.gespielt(")
            )
            assertTrue(
                "$name: onError setzt den Merker nicht",
                q.contains("fehlgeschlagen = true")
            )
        }
    }

    @Test
    fun derFehlerListenerIstVerhaltensneutral() {
        // `false` = nicht behandelt -> Android ruft denselben
        // OnCompletionListener auf wie ohne Listener. Ein `true` waere eine
        // Verhaltensaenderung, und genau die soll hier NICHT stattfinden.
        for (name in listOf("StreamClient.kt", "WakeWordService.kt")) {
            val q = lies(name)
            val ab = q.indexOf("setOnErrorListener")
            assertTrue("$name: kein OnErrorListener", ab > 0)
            val block = q.substring(ab, minOf(ab + 320, q.length))
            assertTrue(
                "$name: der Listener gibt nicht false zurueck - das waere " +
                    "eine Verhaltensaenderung am Audio-Weg",
                block.contains("false")
            )
            assertFalse(
                "$name: der Listener behandelt den Fehler (true) und " +
                    "unterdrueckt damit onCompletion",
                block.contains("true" + System.lineSeparator() + "                }")
            )
        }
    }

    @Test
    fun beideAbspielwegeSindAngeschlossen() {
        val strom = lies("StreamClient.kt")
        assertTrue(
            "StreamClient meldet nicht", strom.contains("probe.melden(")
        )
        assertTrue(
            "StreamClient zaehlt die uebergebenen Bloecke nicht",
            strom.contains("probe.uebergeben()")
        )
        val wake = lies("WakeWordService.kt")
        assertTrue(
            "Der Rueckfall auf /assistant meldet nicht",
            wake.contains("probe.melden(this)")
        )
        assertTrue(
            "Der Rueckfall legt keinen Lauf an",
            wake.contains("TonProbe.Lauf(")
        )
    }

    @Test
    fun esGehtKeinInhaltHinaus() {
        val q = lies("TonProbe.kt")
        for (verboten in listOf("audio_base64", "transcript", "\"text\"")) {
            assertFalse(
                "Die Messung schickt $verboten mit - sie waere damit eine " +
                    "zweite, unverschluesselte Datenspur",
                q.contains(verboten)
            )
        }
        assertTrue(
            "Der Fehlertext wird nicht begrenzt",
            q.contains("fehlertext.take(")
        )
    }

    @Test
    fun dieMessungHaeltDieAntwortNichtAuf() {
        // Sie meldet im Hintergrund - waere der Netzaufruf blockierend,
        // haenge die Antwort an einer reinen Beobachtung.
        val q = lies("TonProbe.kt")
        val ab = q.indexOf("fun melden(")
        assertTrue("melden() fehlt", ab > 0)
        assertTrue(
            "melden() laeuft nicht im Hintergrund",
            q.substring(ab).contains("thread {")
        )
    }
}
