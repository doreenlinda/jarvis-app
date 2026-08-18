package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die drei Schalter liegen eingeklappt hinter einem Knopf (Doreens Wunsch vom
 * 18.08.2026: "damit die App nicht so ueberladen wirkt") - und der Knopf sagt
 * im zugeklappten Zustand, was gerade an ist.
 *
 * WARUM DIESE ZWEITE HAELFTE MITGEPRUEFT WIRD: Die Schalter standen bis dahin
 * ABSICHTLICH offen da; im Layout stand woertlich "bewusst NICHT versteckt,
 * sondern gleich sichtbar". Der Grund war nicht das Bedienen, sondern das
 * SEHEN - ob das WhatsApp-Mitlesen laeuft, war im Alltag schon die
 * entscheidende Frage. Ohne die Kurzuebersicht auf dem Knopf waere das
 * Einklappen ein Rueckschritt. Faellt sie irgendwann weg, faellt es hier auf.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT und belegt nur, dass die
 * Angaben dastehen - nicht, wie es auf dem Handy aussieht. Bei einem
 * Layout-Attribut ist das vertretbar (aapt wendet es an), bei Verhalten waere
 * es das nicht. Kotlin laesst sich auf Doreens Rechner nicht ausfuehren; das
 * hier ist die einzige Pruefung, die VOR dem Ausrollen moeglich ist.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class EinstellungenBereichTest {

    private fun lies(pfad: String): String {
        val datei = File(pfad)
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText()
    }

    private val layout by lazy { lies("src/main/res/layout/activity_main.xml") }
    private val quelle by lazy { lies("src/main/java/com/jarvis/app/MainActivity.kt") }

    /** Der Bereich existiert und ist standardmaessig ZU - sonst waere nichts gewonnen. */
    @Test
    fun derBereichIstStandardmaessigEingeklappt() {
        val start = layout.indexOf("android:id=\"@+id/einstellungenBereich\"")
        assertTrue("Der Einstellungs-Bereich fehlt im Layout", start > 0)
        val kopf = layout.substring(start, minOf(start + 400, layout.length))
        assertTrue(
            "Der Bereich ist nicht eingeklappt - dann bringt der Knopf nichts",
            kopf.substringBefore(">").contains("android:visibility=\"gone\"")
        )
    }

    /**
     * Alle drei Schalter liegen WIRKLICH darin. Ein Schalter, der ausserhalb
     * stehen bleibt, faellt sonst niemandem auf - er sieht ja normal aus.
     */
    @Test
    fun alleDreiSchalterLiegenDarin() {
        val start = layout.indexOf("android:id=\"@+id/einstellungenBereich\"")
        assertTrue("Der Einstellungs-Bereich fehlt im Layout", start > 0)
        val ende = layout.indexOf("</LinearLayout>", start)
        assertTrue("Der Bereich wird nicht geschlossen", ende > start)
        val innen = layout.substring(start, ende)
        for (id in listOf("standortSchalter", "whatsappSchalter", "inhaltVorlesenSchalter")) {
            assertTrue(
                "Der Schalter '" + id + "' liegt NICHT im eingeklappten Bereich",
                innen.contains("@+id/" + id)
            )
        }
        // Gegenprobe: Die Statuszeile bleibt AUSSERHALB sichtbar. Sie nennt die
        // Versionsnummer und den Mikro-Pegel - genau damit war am 18.08. zu
        // klaeren, dass der Weckwort-Dienst lief. Ein Bild ersetzt keine Diagnose.
        assertTrue(
            "Die Statuszeile darf nicht mit eingeklappt werden",
            !innen.contains("@+id/wakeStatusView")
        )
    }

    /** Der Knopf traegt zugeklappt den Stand aller drei Schalter. */
    @Test
    fun derKnopfZeigtDenStandDerSchalter() {
        assertTrue(
            "Die Kurzuebersicht fehlt",
            quelle.contains("private fun aktualisiereEinstellungsUebersicht()")
        )
        for (name in listOf("Standort", "WhatsApp", "Vorlesen")) {
            assertTrue(
                "Die Uebersicht nennt '" + name + "' nicht",
                quelle.contains("\"" + name + "\" to R.id.")
            )
        }
        assertTrue(
            "Die Uebersicht unterscheidet an und aus nicht",
            quelle.contains("\" an\"") && quelle.contains("\" aus\"")
        )
    }

    /**
     * Die Uebersicht muss bei JEDER Statusauffrischung mitlaufen - sonst zeigt
     * der Knopf einen veralteten Stand, und das ist schlimmer als gar keiner.
     */
    @Test
    fun dieUebersichtWirdUeberallNachgezogen() {
        val aufrufe = quelle.split("aktualisiereEinstellungsUebersicht()").size - 1
        // 1x Definition, 1x im Klick des Knopfes, 3x in den Status-Funktionen
        assertEquals(
            "Nicht jede Stelle zieht die Uebersicht nach (erwartet 5 Vorkommen)",
            5, aufrufe
        )
        for (name in listOf("zeigeStandort", "zeigeVorlesenStatus", "zeigeWhatsAppStatus")) {
            val start = quelle.indexOf("private fun " + name + "() {")
            assertTrue("Funktion " + name + " fehlt", start > 0)
            val rumpfAnfang = quelle.substring(start, minOf(start + 200, quelle.length))
            assertTrue(
                name + " zieht die Uebersicht nicht nach",
                rumpfAnfang.contains("aktualisiereEinstellungsUebersicht()")
            )
        }
    }
}
