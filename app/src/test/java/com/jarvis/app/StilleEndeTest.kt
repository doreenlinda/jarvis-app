package com.jarvis.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Aufnahme endet nach einer Sprechpause - und zwar ERST, nachdem
 * ueberhaupt gesprochen wurde.
 *
 * ANLASS (06.09.2026): Doreens Auftrag "bis er reagiert, dauert immer noch
 * sehr lange". Gemessen wurde zuerst, wo die Zeit hingeht - sie schickte
 * denselben Satz einmal zugerufen (5,02 s) und einmal getippt (5,49 s).
 * Die Spracherkennung kostet also nur rund eine Sekunde, und serverseitig
 * ist kaum etwas zu holen: 4 von 5 Sekunden gehoeren Anthropic und
 * ElevenLabs. Der zweite gehandelte Kandidat, der Router, wurde ueber alle
 * 17 Laeufe des Tages gemessen und AUSGESCHLOSSEN (in 15 davon war die
 * Vertonung der letzte Baustein vor dem ersten Ton). Damit blieb dieser
 * Wert als einziger echter Hebel uebrig; Doreen waehlte 800 ms aus drei
 * vorgelegten Werten.
 *
 * WARUM DIESER TEST NICHT AUF 800 FESTNAGELT: Der Wert ist zum Justieren
 * gedacht - faellt er ihr ins Wort, geht er wieder hoch. Ein Test, der eine
 * bestimmte Zahl verlangt, wuerde bei genau der Ruecknahme rot, die
 * erwuenscht ist. Geprueft wird deshalb ein sinnvoller BEREICH und - viel
 * wichtiger - die MECHANIK dahinter.
 *
 * DIE MECHANIK IST DER EIGENTLICHE SCHUTZ: `stilleMs` darf erst zaehlen,
 * wenn `gesprochen` true ist. Sonst wuerde eine Denkpause VOR dem ersten
 * Wort die Aufnahme sofort beenden, und bei 800 ms faellt das viel eher
 * ins Gewicht als bei 1300. Wer die beiden Zweige vertauscht, macht das
 * Weckwort unbrauchbar - und zwar still.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Ob sich 800 ms im Alltag
 * richtig anfuehlen, entscheidet allein Doreens Erfahrung.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class StilleEndeTest {

    private val quelle: String by lazy {
        val datei = File("src/main/java/com/jarvis/app/WakeWordService.kt")
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        datei.readText()
    }

    private fun wert(name: String): Int {
        val marke = "$name = "
        val i = quelle.indexOf(marke)
        assertTrue("$name steht nicht im Quelltext", i >= 0)
        // Unterstriche gehoeren dazu: AUFNAHME_MAX_MS steht als 30_000 da,
        // und takeWhile(isDigit) haette daraus eine 30 gemacht.
        return quelle.substring(i + marke.length)
            .takeWhile { it.isDigit() || it == '_' }
            .replace("_", "")
            .toInt()
    }

    @Test
    fun stilleEndeLiegtInEinemSinnvollenBereich() {
        val ms = wert("STILLE_ENDE_MS")
        // Untergrenze: Unter einer halben Sekunde schneidet er in fast jedem
        // laengeren Satz mit. Obergrenze: Darueber war der Wert vor dem
        // 06.09. schon, und laenger zu warten war ausdruecklich das Problem.
        assertTrue("STILLE_ENDE_MS=$ms ist zu kurz - er faellt ins Wort", ms >= 600)
        assertTrue("STILLE_ENDE_MS=$ms ist zu lang - das war der Anlass", ms <= 1500)
    }

    @Test
    fun stilleZaehltErstNachDemErstenWort() {
        // Der Zaehler muss im else-Zweig von "gesprochen" stehen. Ohne das
        // beendet eine Pause VOR dem ersten Wort die Aufnahme sofort.
        val i = quelle.indexOf("stilleMs += 80")
        assertTrue("stilleMs wird nirgends hochgezaehlt", i >= 0)
        val davor = quelle.substring(maxOf(0, i - 200), i)
        assertTrue(
            "stilleMs zaehlt nicht mehr im else-Zweig von 'gesprochen' - " +
                "eine Denkpause vor dem ersten Wort wuerde die Aufnahme beenden",
            davor.contains("else if (gesprochen)"),
        )
    }

    @Test
    fun ohneGesprochenesWortWirdNichtsGesendet() {
        // Zweite Haelfte derselben Sicherung: Kommt gar kein Wort, entsteht
        // keine Datei. Sonst gingen Raumgeraeusche als Zuruf an den Server.
        assertTrue(
            "Die Pruefung 'if (!gesprochen) return null' fehlt",
            quelle.contains("if (!gesprochen) return null"),
        )
    }

    @Test
    fun dieObergrenzeDerAufnahmeBleibtDeutlichDarueber() {
        // Am 27.07.2026 von 10 s auf 30 s erhoeht, weil ihre Zurufe laenger
        // geworden sind. Die Stille-Erkennung beendet die Aufnahme im
        // Normalfall; dieser Deckel greift nur beim Durchsprechen.
        val stille = wert("STILLE_ENDE_MS")
        val deckel = wert("AUFNAHME_MAX_MS")
        assertTrue(
            "AUFNAHME_MAX_MS=$deckel ist zu nah an STILLE_ENDE_MS=$stille",
            deckel >= stille * 10,
        )
    }
}
