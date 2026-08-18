package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Reihenfolge auf dem Hauptschirm (v0.44, Doreens Vorschlag vom
 * 18.08.2026): "Dann muss 'Zugangsdaten einblenden' runter zu den anderen
 * Buttons, oder, dann haetten wir mehr Platz."
 *
 * WARUM ES DIESEN TEST GIBT: Der Zugangsdaten-Knopf war das einzige Element
 * zwischen Orb und Eingabefeld. Seine ~48dp sind in die Hoehe des Orbs
 * geflossen (180dp -> 230dp), und erst dadurch passt die grosse Glaskugel
 * MIT ihrem Pulsieren in die Flaeche. Wandert der Knopf zurueck nach oben,
 * ohne dass jemand die Orb-Hoehe mitzieht, stoesst die Kugel beim Sprechen
 * oben und unten an - und das sieht man nicht im Quelltext, sondern erst
 * auf dem Handy.
 *
 * Die beiden Angaben haengen also zusammen und werden hier zusammen
 * geprueft.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Er belegt, dass die Angaben
 * dastehen - nicht, wie es auf dem Handy aussieht.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class AufbauTest {

    private fun layout(): String {
        val datei = File("src/main/res/layout/activity_main.xml")
        assertTrue("Layout nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText()
    }

    @Test
    fun derZugangsknopfStehtUnterDenTasten() {
        val l = layout()
        val orb = l.indexOf("@+id/voiceOrb")
        val eingabe = l.indexOf("@+id/messageText")
        val sprechen = l.indexOf("@+id/talkButton")
        val zugang = l.indexOf("@+id/zugangToggle")
        assertTrue("voiceOrb fehlt", orb >= 0)
        assertTrue("messageText fehlt", eingabe >= 0)
        assertTrue("talkButton fehlt", sprechen >= 0)
        assertTrue("zugangToggle fehlt", zugang >= 0)

        assertTrue(
            "Zwischen Orb und Eingabefeld steht wieder etwas - der Platz gehoert dem Orb",
            orb < eingabe && zugang > eingabe
        )
        assertTrue(
            "Der Zugangsdaten-Knopf ist wieder vor die Sprechen-Taste gerutscht",
            zugang > sprechen
        )
    }

    @Test
    fun derOrbBehaeltSeineHoehe() {
        val block = layout().substringAfter("<com.jarvis.app.VoiceOrbView").substringBefore("/>")
        assertTrue(
            "Der Orb ist nicht mehr 230dp hoch - die grosse Kugel wuerde beim " +
                "Sprechen oben und unten anstossen",
            block.contains("android:layout_height=\"230dp\"")
        )
    }

    /**
     * Die vier Haupttasten stehen in EINER Reihe (Doreens Wunsch vom
     * 18.08.2026: "kleiner Senden, Sprechen, Foto/Scannen, Stoppen ... das
     * wuerde auch alles nochmal aufgeraeumter wirken lassen"). Vorher waren
     * es vier breite Balken untereinander.
     *
     * Die Breite kommt aus layout_weight - ohne das waere eine Taste so
     * breit wie ihre gerade eingestellte Beschriftung, und die Reihe
     * huepfte, sobald der Weckwort-Knopf zwischen "Aktivieren" und
     * "Stoppen" wechselt.
     */
    @Test
    fun dieVierHaupttastenStehenNebeneinander() {
        val l = layout()
        val reihe = l.substringAfter("android:orientation=\"horizontal\"", "")
            .substringBefore("</LinearLayout>")
        assertTrue("Es gibt keine waagerechte Tastenreihe mehr", reihe.isNotEmpty())
        listOf("sendButton", "talkButton", "cameraButton", "wakeButton").forEach { name ->
            assertTrue(
                "Die Taste '" + name + "' steht nicht mehr in der Reihe",
                reihe.contains("@+id/" + name)
            )
        }
        assertEquals(
            "Nicht jede Taste der Reihe teilt sich die Breite (layout_weight)",
            4,
            reihe.split("android:layout_weight=\"1\"").size - 1
        )
    }

    /**
     * KEINE Symbole auf den Tasten (ihr Wunsch, gleicher Tag). Geprueft wird
     * das Layout UND MainActivity: Drei der Beschriftungen werden zur
     * Laufzeit neu gesetzt - wer nur das Layout saeubert, holt sich die
     * Symbole beim ersten Antippen zurueck.
     *
     * Die Schwelle liegt bei U+2300, damit Umlaute, Gedankenstriche und
     * typografische Anfuehrungszeichen ausdruecklich erlaubt bleiben.
     */
    @Test
    fun keineSymboleAufDenTasten() {
        val stellen = mutableListOf<String>()
        layout().split("<Button").drop(1).forEach { roh ->
            val block = roh.substringBefore("/>")
            if (block.contains("android:text=\"")) {
                stellen += block.substringAfter("android:text=\"").substringBefore("\"")
            }
        }
        val quelle = File("src/main/java/com/jarvis/app/MainActivity.kt").readText()
        quelle.lines().filter { it.contains("Button.text = \"") || it.contains("\"Stoppen\"") }
            .forEach { stellen += it }

        stellen.forEach { text ->
            val symbol = text.firstOrNull { it.code >= 0x2300 }
            assertTrue(
                "Symbol '" + symbol + "' in einer Tastenbeschriftung: " + text.trim(),
                symbol == null
            )
        }
    }

    /**
     * Beim ersten Start klappt der Zugangsbereich von selbst auf. Da er
     * jetzt UNTEN steht, liegt er dabei ausserhalb des Bildes - ohne das
     * Scrollen saehe Doreen eine App ohne Eingabefelder und wuesste nicht,
     * wohin. Das ist der Preis der neuen Reihenfolge und muss bezahlt
     * bleiben.
     */
    @Test
    fun beimErstenStartWirdZumZugangsbereichGescrollt() {
        val quelle = File("src/main/java/com/jarvis/app/MainActivity.kt").readText()
        val stelle = quelle.substringAfter("nochNichtEingerichtet", "")
        assertTrue(
            "Es wird beim ersten Start nicht mehr zum Zugangsbereich gescrollt",
            stelle.contains("smoothScrollTo")
        )
    }
}
