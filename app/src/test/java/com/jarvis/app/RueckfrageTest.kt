package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Rueckfrage nach dem Weckwort kommt VOR ihrer Frage - lokal.
 *
 * ANLASS (07.09.2026, Doreens Beobachtung im Wortlaut): "Das 'Was wollen
 * Sie denn schon wieder' muesste ja vor meiner Frage kommen. So waere doch
 * die klaffende Luecke weg oder nicht?"
 *
 * Ihre ERKLAERUNG traf nicht zu (der Statustext blockiert nichts), ihre
 * BEOBACHTUNG aber sehr wohl - und der gemessene Befund war schlimmer als
 * vermutet: Ruft sie und schweigt, laeuft die Aufnahme bis
 * AUFNAHME_MAX_MS, also 30 Sekunden. Danach steht "if (!gesprochen) return
 * null" - es geht nicht einmal etwas an den Server, und die serverseitige
 * Rueckfrage vom 05.09. kann deshalb gar nicht greifen.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Kotlin laesst sich auf dem
 * Laptop nicht ausfuehren, und AudioRecord gibt es im Unit-Test nicht - ein
 * echter Ablauftest ist hier unmoeglich. Was er kann, ist die drei Gefahren
 * abdecken, die im Quelltext unsichtbar sind:
 *
 *   1. Jarvis' EIGENE Stimme darf nicht als ihre Frage ankommen. Der
 *      Mikrofonpuffer laeuft waehrend des Abspielens weiter; wird er nicht
 *      verworfen, transkribiert Groq die Rueckfrage und der Server
 *      beantwortet sie. Das ist die teuerste Gefahr hier.
 *   2. Im NACHFASS-Fenster darf sie nie kommen. Dort wird dieselbe Funktion
 *      erst gerufen, nachdem Doreen schon spricht - eine Rueckfrage fiele
 *      ihr ins Wort.
 *   3. Die Obergrenze ohne gesprochenes Wort muss deutlich unter
 *      AUFNAHME_MAX_MS liegen, sonst bleibt es beim 30-Sekunden-Fall.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class RueckfrageTest {

    private val dienst =
        File("src/main/java/com/jarvis/app/WakeWordService.kt").readText()
    private val modul =
        File("src/main/java/com/jarvis/app/Rueckfrage.kt").readText()

    /** Der Block einer Funktion ab ihrer Signatur bis zur naechsten. */
    private fun abschnitt(quelle: String, ab: String, bis: String): String {
        val a = quelle.indexOf(ab)
        assertTrue("Anker fehlt: " + ab, a >= 0)
        val b = quelle.indexOf(bis, a + ab.length)
        return if (b > a) quelle.substring(a, b) else quelle.substring(a)
    }

    private fun zahl(name: String): Int {
        val marke = "val " + name + " = "
        val i = dienst.indexOf(marke)
        assertTrue("Konstante fehlt: " + name, i >= 0)
        val roh = dienst.substring(i + marke.length)
            .takeWhile { it.isDigit() || it == '_' }
        return roh.replace("_", "").toInt()
    }

    // ---- 1. Der Mikrofonpuffer wird verworfen --------------------------
    @Test
    fun nachDemAbspielenWirdDerMikrofonpufferVerworfen() {
        val block = abschnitt(
            dienst, "if (Rueckfrage.abspielen(this))", "if (!rueckfrageOffen"
        )
        assertTrue(
            "Der Mikrofonpuffer muss nach dem Abspielen verworfen werden - " +
                "sonst kommt Jarvis' eigene Stimme als ihre Frage an.",
            block.contains("rec.stop()") && block.contains("rec.startRecording()")
        )
        assertTrue(
            "Das schon Aufgenommene faellt ebenfalls weg (daten.reset()).",
            block.contains("daten.reset()")
        )
    }

    // ---- 2. Nur beim Weckwort, nie im Nachfass-Fenster -----------------
    @Test
    fun nurDerWeckwortFallBekommtDieRueckfrage() {
        assertTrue(
            "aufnehmenAusStrom muss die Rueckfrage abschalten koennen.",
            dienst.contains("mitRueckfrage: Boolean = false")
        )
        assertTrue(
            "Der Weckwort-Fall schaltet sie ein.",
            abschnitt(dienst, "private fun nimmFrageAufAusStrom", "\n    /**")
                .contains("mitRueckfrage = true")
        )
        val nachfass = abschnitt(
            dienst, "private fun nachfassFenster", "private fun alsWav"
        )
        assertTrue(
            "Im Nachfass-Fenster spricht sie schon - dort darf keine " +
                "Rueckfrage dazwischenfahren.",
            !nachfass.contains("mitRueckfrage = true")
        )
    }

    // ---- 3. Der 30-Sekunden-Fall ist weg ------------------------------
    @Test
    fun ohneGesprochenesWortIstFrueherSchluss() {
        val ohneWort = zahl("OHNE_WORT_ENDE_MS")
        val deckel = zahl("AUFNAHME_MAX_MS")
        assertTrue(
            "Ohne ein einziges Wort darf das Mikrofon nicht bis zum " +
                "Deckel offen bleiben (Livefall: " + deckel + " ms).",
            ohneWort < deckel / 2
        )
        assertTrue(
            "Aber lang genug, dass sie nach der Rueckfrage antworten kann.",
            ohneWort >= 3_000
        )
        assertTrue(
            "Die Obergrenze muss im Code auch geprueft werden.",
            dienst.contains("laufzeitMs >= OHNE_WORT_ENDE_MS")
        )
    }

    @Test
    fun dieRueckfrageKommtVorDerObergrenze() {
        assertTrue(
            "Erst nachfragen, dann aufgeben - sonst wird sie nie gestellt.",
            zahl("RUECKFRAGE_NACH_MS") < zahl("OHNE_WORT_ENDE_MS")
        )
        assertTrue(
            "Und nicht so frueh, dass ein in einem Zug gesprochenes " +
                "'Hey Jarvis, wann ...' sie zu hoeren bekommt.",
            zahl("RUECKFRAGE_NACH_MS") >= 1_000
        )
    }

    // ---- 4. Sie feuert nur, solange nicht gesprochen wurde -------------
    @Test
    fun werSprichtBekommtKeineRueckfrage() {
        val schleife = abschnitt(
            dienst,
            "var rueckfrageOffen = mitRueckfrage",
            "if (!gesprochen) return null"
        )
        val sprungMarke = schleife.indexOf("if (gesprochen) continue")
        val rueckfrage = schleife.indexOf("rueckfrageOffen && laufzeitMs")
        assertTrue(
            "Beide Stellen muessen vorhanden sein.",
            sprungMarke >= 0 && rueckfrage >= 0
        )
        assertTrue(
            "Der Rueckfrage-Zweig gehoert HINTER die Pegelauswertung - " +
                "sonst feuert er im selben Block, in dem sie zu sprechen " +
                "beginnt.",
            sprungMarke < rueckfrage
        )
        assertTrue(
            "Sie wird genau einmal gestellt.",
            schleife.contains("rueckfrageOffen = false")
        )
    }

    // ---- 5. Assets und Rotation ---------------------------------------
    @Test
    fun esGibtSoVieleDateienWieDasModulErwartet() {
        val marke = "val ANZAHL = "
        val i = modul.indexOf(marke)
        assertTrue("ANZAHL fehlt", i >= 0)
        val anzahl = modul.substring(i + marke.length)
            .takeWhile { it.isDigit() }.toInt()
        val ordner = File("src/main/assets")
        val da = (0 until anzahl).count {
            val f = File(ordner, "rueckfrage_" + it + ".mp3")
            f.isFile && f.length() > 5_000
        }
        assertEquals(
            "Jede Wendung braucht ihre Tondatei - eine fehlende faellt " +
                "sonst erst im Alltag auf, und zwar als Schweigen.",
            anzahl, da
        )
    }

    @Test
    fun dieWendungenWechselnSichAb() {
        assertTrue(
            "Dieselbe Wendung jedes Mal nutzt sich ab - dieselbe Lektion " +
                "wie bei der Anrede und den Abschluss-Wendungen.",
            modul.contains("% ANZAHL")
        )
        assertTrue(
            "Ein Fehlschlag darf das Zuhoeren nie mitreissen.",
            modul.contains("catch (_: Throwable)")
        )
        assertTrue(
            "Der Fokus wird in JEDEM Fall wieder freigegeben - ein " +
                "haengender liesse die Musik dauerhaft leise.",
            abschnitt(modul, "} finally {", "private fun naechsteNummer")
                .contains("fokusFreigeben")
        )
    }
}
