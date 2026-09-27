package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fliessendes Gespraech ohne erneutes "Hey Jarvis" (v0.61, 27.09.2026).
 *
 * Zwei Gefahren bewacht dieser Test, und sie liegen in entgegengesetzten
 * Richtungen:
 *   1. ZU ZU: Die Kette bricht nach jeder Antwort ab - dann muss sie wieder
 *      jede zweite Runde rufen (der Zustand bis v0.60, ihr Anlass).
 *   2. ZU OFFEN: Die Kette laeuft endlos weiter - dann haelt ein
 *      aufgeschnapptes Raumgespraech das Mikrofon offen (02.08.2026: 17
 *      Wortwechsel in 17 Minuten).
 *
 * Die Entscheidung selbst (Gespraech.weiterhoeren) wird AUSGEFUEHRT. Die
 * Verdrahtung wird am Quelltext geprueft - AudioRecord und OkHttp laufen im
 * Unit-Test nicht. Das faengt das versehentliche Aushaengen beim Umbau,
 * nicht jede absichtliche Aushebelung.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class GespraechTest {

    private fun quelle(name: String): String {
        val datei = File("src/main/java/com/jarvis/app/$name")
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText().replace("\r\n", "\n")
    }

    // ---------------------------------------------------------- Entscheidung

    @Test
    fun nachDemWeckwortGehtDasFensterImmerAuf() {
        // Auch nach einer ratlosen ersten Antwort: Dann will sie die Frage
        // ja gerade wiederholen.
        assertTrue(Gespraech.weiterhoeren(0, false))
        assertTrue(Gespraech.weiterhoeren(0, true))
    }

    @Test
    fun einOffenesGespraechLaeuftWeiter() {
        // DER ANLASS: bis v0.60 war hier nach der ersten Nachfrage Schluss.
        assertTrue(Gespraech.weiterhoeren(1, true))
        assertTrue(Gespraech.weiterhoeren(3, true))
    }

    @Test
    fun eineRatloseAntwortBeendetDieKette() {
        // Das Bild eines Raumgespraechs - hier muss es enden.
        assertFalse(Gespraech.weiterhoeren(1, false))
        assertFalse(Gespraech.weiterhoeren(4, false))
    }

    @Test
    fun dieObergrenzeGreiftAuchBeiOffenemGespraech() {
        val max = Gespraech.MAX_NACHFRAGEN
        assertTrue(Gespraech.weiterhoeren(max - 1, true))
        assertFalse(Gespraech.weiterhoeren(max, true))
        assertFalse(Gespraech.weiterhoeren(max + 5, true))
    }

    @Test
    fun dieObergrenzeIstSinnvoll() {
        // Nicht festgenagelt (zum Justieren gedacht), aber in einem Bereich:
        // unter 3 ist es wieder das alte Hin und Her, ueber 15 verliert die
        // Grenze ihre Schutzwirkung gegen ein Raumgespraech.
        val max = Gespraech.MAX_NACHFRAGEN
        assertTrue("MAX_NACHFRAGEN=$max", max in 3..15)
    }

    // ------------------------------------------------------------ Verdrahtung

    @Test
    fun dieSchleifeFragtDieEntscheidung() {
        val w = quelle("WakeWordService.kt")
        assertTrue("Die Schleife ruft Gespraech.weiterhoeren nicht auf",
            w.contains("Gespraech.weiterhoeren(nachfragen, offen)"))
        assertTrue("Das Ergebnis von frageJarvis wird nicht verwendet",
            w.contains("val offen = frageJarvis(frage, nachfragen > 0)"))
        // Die alte Sperre darf nicht zurueckkommen - sonst waere es wieder
        // nach EINER Nachfrage vorbei.
        assertFalse("Die alte v0.30-Sperre steht wieder im Code",
            w.contains("if (aktiv && !ausNachfass) nachfassFenster()"))
    }

    @Test
    fun einFehlendesFeldGiltAlsZu() {
        // Aeltere Server, Stoermeldungen und "nichts verstanden" tragen das
        // Feld nicht. Der Standard MUSS false sein - sonst hielte ein
        // Serverfehler die Kette offen.
        val s = quelle("StreamClient.kt")
        assertTrue("Strom liest gespraech_offen nicht mit Standard false",
            s.contains("ev.optBoolean(\"gespraech_offen\", false)"))
        val w = quelle("WakeWordService.kt")
        assertTrue("/assistant-Rueckfall liest gespraech_offen nicht mit Standard false",
            w.contains("json.optBoolean(\"gespraech_offen\", false)"))
    }

    @Test
    fun frageJarvisMeldetImZweifelZu() {
        val w = quelle("WakeWordService.kt")
        val anfang = w.indexOf("private fun frageJarvis(")
        val ende = w.indexOf("private fun spieleAntwort(", anfang)
        assertTrue("frageJarvis nicht gefunden", anfang >= 0 && ende > anfang)
        val f = w.substring(anfang, ende)
        assertTrue("frageJarvis gibt keinen Boolean zurueck", f.contains("): Boolean {"))
        val wahr = f.split("return true").size - 1
        assertEquals("frageJarvis darf nie pauschal 'true' melden", 0, wahr)
    }
}
