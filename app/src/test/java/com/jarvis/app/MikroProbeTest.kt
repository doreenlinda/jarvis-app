package com.jarvis.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Mikrofon-Messung meldet, wie laut ihre Frage wirklich ankam.
 *
 * ANLASS (17./18.09.2026): Doreen meldete aus dem Auto, in der App habe
 * "Keine Frage gehoert - ich lausche weiter" gestanden, obwohl sie
 * gesprochen hatte. Beim Server kam nichts an - und das ist kein Zufall:
 * `aufnehmenAusStrom` sendet bei `if (!gesprochen) return null` GAR NICHTS.
 *
 * Die vorhandene Pegel-Anzeige kann die Frage nicht beantworten: Sie laeuft
 * nur in der Lausch-Schleife, nicht waehrend der Aufnahme. Genau das hat
 * Doreen selbst beobachtet ("beim Sprechen war der Pegel nicht sichtbar").
 * Dazu ihr eigener Einwand: Beim FAHREN kann sie ohnehin nichts ablesen -
 * deshalb geht die Zahl zum Server statt auf den Schirm.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Kotlin laesst sich auf dem
 * Laptop nicht ausfuehren, und AudioRecord gibt es im Unit-Test nicht. Was
 * er kann, ist die vier Gefahren abdecken, die im Quelltext unsichtbar
 * sind - und er laeuft im Cloud-Build VOR dem Bauen, es entsteht also gar
 * keine APK, wenn eine davon eintritt:
 *
 *   1. Steht die Meldung HINTER dem Abbruch, wird ausgerechnet der Fall nie
 *      gemeldet, fuer den die Messung gebaut wurde. Das ist die teuerste
 *      Gefahr hier - sie faellt im Alltag nicht auf, weil dann einfach
 *      nichts im Protokoll steht, und "nichts" sieht aus wie "alles gut".
 *   2. Wird beim Rueckfrage-Reset nicht auch die Messung zurueckgesetzt,
 *      mischt sich der Pegel von VOR der Rueckfrage in die Zahl - gemessen
 *      werden soll aber ihre ANTWORT darauf.
 *   3. Die Messung darf SPRACH_PEGEL nicht anfassen. Die Schwelle ist die
 *      Sicherung gegen aufgeschnappte Raumgespraeche (neun Fehlausloesungen
 *      am 26.07.2026); wer sie im Zuge einer Messung aendert, hat nicht
 *      gemessen, sondern eingegriffen - und weiss hinterher nicht, was
 *      wovon kam.
 *   4. Der Netzaufruf muss nebenlaeufig sein. Auch ein GELUNGENER Zuruf
 *      wird gemeldet (ohne Nenner ist die Fehlerzahl wertlos) - er darf
 *      dadurch aber keine Sekunde langsamer werden.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class MikroProbeTest {

    private fun dienst(): String =
        File("src/main/java/com/jarvis/app/WakeWordService.kt").readText()

    private fun probe(): String =
        File("src/main/java/com/jarvis/app/MikroProbe.kt").readText()

    @Test
    fun dieMeldungStehtVorDemAbbruch() {
        val s = dienst()
        val melde = s.indexOf("MikroProbe.melde(")
        val abbruch = s.indexOf("if (!gesprochen) return null")
        assertTrue("MikroProbe.melde wird gar nicht gerufen", melde >= 0)
        assertTrue("der Abbruch ist nicht mehr da", abbruch >= 0)
        assertTrue(
            "Die Meldung steht HINTER dem Abbruch - damit wird genau der " +
                "Fall nie gemeldet, fuer den die Messung gebaut wurde.",
            melde < abbruch
        )
    }

    @Test
    fun dieQuelleWirdInDerAufnahmeschleifeAbgelesen() {
        // WER HAT DAS MIKROFON (v0.59): getRoutedDevice() liefert nur
        // WAEHREND der laufenden Aufnahme einen Wert. Rutscht das Ablesen
        // hinter die Schleife, steht im Protokoll dauerhaft nichts - und
        // die Messung ist blind, ohne dass es auffaellt.
        //
        // Genau dieser Zeitpunkt-Fehler hat die Ton-Umleitung aus v0.57
        // wirkungslos gemacht: Sie fragte VOR dem Start.
        val s = dienst()
        val lesen = s.indexOf("rec.routedDevice")
        val schleife = s.indexOf("while (aktiv && laufzeitMs < AUFNAHME_MAX_MS)")
        // Die LETZTE Anweisung der Schleife als Grenze. Ohne sie wuerde nur
        // die Reihenfolge dreier Textstellen geprueft - und die stimmt auch
        // dann noch, wenn der Block hinter die Schleife wandert. Genau das
        // ist beim Sabotagelauf am 18.09.2026 passiert.
        val ende = s.indexOf("if (!rueckfrageOffen && laufzeitMs >= OHNE_WORT_ENDE_MS)")
        assertTrue("Die Aufnahmequelle wird nirgends abgelesen.", lesen > 0)
        assertTrue("Schleifenanfang oder -ende nicht gefunden.",
            schleife > 0 && ende > 0)
        assertTrue(
            "Die Quelle wird NICHT innerhalb der Aufnahmeschleife " +
                "abgelesen - getRoutedDevice liefert dann nichts.",
            schleife < lesen && lesen < ende
        )
        assertTrue(
            "Die abgelesene Quelle wird nicht an die Messung weitergereicht.",
            s.contains("quelle = quelle")
        )
    }

    @Test
    fun fehlendeAngabenWerdenNichtErfunden() {
        // Ein leeres Feld statt "-1": Der Server soll "nicht gemeldet" von
        // "unbekanntes Geraet" unterscheiden koennen. Sonst steht in genau
        // der Zeile, an der sich alles entscheidet, eine erfundene Angabe.
        val p = probe()
        assertTrue(
            "Die Quelle wird auch dann gesendet, wenn sie unbekannt ist.",
            p.contains("if (quelle >= 0) quelle.toString() else")
        )
        assertTrue(
            "Scheitert die Geraeteabfrage, muss der Pegel trotzdem raus.",
            p.contains("var vorhanden = ")
        )
    }

    @Test
    fun beideFaelleWerdenGemeldet() {
        // Der Aufruf darf nicht in einem "if (gesprochen)" haengen: Ohne
        // den Nenner ist die Zahl der Fehlschlaege wertlos.
        val s = dienst()
        val melde = s.indexOf("MikroProbe.melde(")
        assertTrue("MikroProbe.melde fehlt", melde >= 0)
        val davor = s.substring(maxOf(0, melde - 200), melde)
        assertTrue(
            "Die Meldung haengt an einer Bedingung - dann fehlt der Nenner " +
                "fuer die Auswertung.",
            !davor.contains("if (gesprochen)")
        )
        assertTrue(
            "Der gemeldete Wert `gesprochen` wird nicht durchgereicht.",
            s.contains("gesprochen = gesprochen")
        )
    }

    @Test
    fun derRueckfrageResetSetztAuchDieMessungZurueck() {
        // Nach der Rueckfrage faengt die Aufnahme von vorn an (daten.reset,
        // laufzeitMs = 0). Bleiben die Pegel-Zaehler stehen, misst die
        // Meldung die Stille VOR der Rueckfrage mit.
        val s = dienst()
        val reset = s.indexOf("daten.reset()")
        assertTrue("der Rueckfrage-Reset ist nicht mehr da", reset >= 0)
        val block = s.substring(reset, minOf(s.length, reset + 600))
        assertTrue(
            "maxPegel wird beim Rueckfrage-Reset nicht zurueckgesetzt - die " +
                "Messung enthielte dann die Phase VOR der Rueckfrage.",
            block.contains("maxPegel = 0")
        )
        assertTrue(
            "pegelBloecke wird beim Rueckfrage-Reset nicht zurueckgesetzt.",
            block.contains("pegelBloecke = 0")
        )
    }

    @Test
    fun dieSchwelleBleibtUnangetastet() {
        assertTrue(
            "SPRACH_PEGEL steht nicht mehr auf 1500. Die Messung soll NICHTS " +
                "veraendern - sonst ist hinterher nicht trennbar, was von der " +
                "Messung und was von der Aenderung kam.",
            dienst().contains("SPRACH_PEGEL = 1500")
        )
    }

    @Test
    fun dieSchwelleWirdMitgemeldet() {
        // Wird sie spaeter doch verstellt, deutet das Protokoll sonst still
        // falsch: 900 ist bei Schwelle 1000 ein knapper Fall, bei 1500 nicht.
        assertTrue(
            "Die eigene Schwelle wird nicht mitgeschickt.",
            dienst().contains("schwelle = SPRACH_PEGEL")
        )
        assertTrue(
            "MikroProbe schickt das Feld schwelle nicht.",
            probe().contains("\"schwelle\"")
        )
    }

    @Test
    fun derAufrufBremstDenZurufNicht() {
        assertTrue(
            "MikroProbe meldet nicht nebenlaeufig - ein gelungener Zuruf " +
                "wuerde dadurch langsamer.",
            probe().contains("thread {")
        )
    }

    @Test
    fun esGehenNurZahlenRaus() {
        // Kein Ton, kein Transkript: Sonst waere es keine Messung mehr,
        // sondern eine zweite, unverschluesselte Uebertragung ihrer Worte.
        val p = probe().lowercase()
        for (verboten in listOf("\"audio\"", "\"text\"", "\"transkript\"", "\"frage\"")) {
            assertTrue(
                "MikroProbe schickt ein Feld $verboten - hier duerfen nur " +
                    "Zahlen raus.",
                !p.contains(verboten)
            )
        }
    }
}
