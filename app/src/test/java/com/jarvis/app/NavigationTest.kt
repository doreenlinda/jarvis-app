package com.jarvis.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Zielfuehrung: "Navigier mich zu Nicki" - und Maps geht auf (v0.60).
 *
 * Der Server kann nicht navigieren, er kann nur bitten: Der Befehl kommt
 * ueber den NDJSON-Strom, ausgeloest wird der Intent hier. Dieselbe
 * Bauart wie der WhatsApp-Versand seit v0.38.
 *
 * WARUM ES DIESEN TEST GIBT - vier Gefahren, die beim Drueberlesen nicht
 * auffallen:
 *
 *   1. FLAG_ACTIVITY_NEW_TASK. Der Aufruf kommt aus dem Weckwort-DIENST,
 *      und ein Dienst hat keine Activity, von der aus sich eine neue
 *      oeffnen koennte. Ohne das Flag wirft Android - und zwar erst auf
 *      ihrem Handy, waehrend sie im Auto sitzt.
 *   2. Uri.encode. Ihre Zonenadressen tragen Leerzeichen, Kommata und
 *      Umlaute ("Am Rötepfuhl 35, 12349 Berlin"). Ungekodiert bricht der
 *      Intent oder landet woanders.
 *   3. DIE REIHENFOLGE der beiden Versuche. Erst gezielt Google Maps,
 *      dann ohne Paketangabe. Andersherum ginge bei jeder Fahrt ein
 *      Auswahldialog auf - genau die Zumutung, die der Zuruf ersparen soll.
 *   4. KEIN STUMMES SCHEITERN. Auf dem Handy gibt es kein lesbares Log;
 *      genau daran scheiterte die Weckwort-Fehlersuche bis v0.7.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Intent, Uri und Context
 * gibt es im Unit-Test nicht, ein echter Ablauftest ist hier unmoeglich.
 * Er faengt das versehentliche Entfernen beim naechsten Umbau - die
 * realistische Gefahr -, nicht jede absichtliche Aushebelung.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class NavigationTest {

    private val navigation =
        File("src/main/java/com/jarvis/app/Navigation.kt").readText()
    private val stream =
        File("src/main/java/com/jarvis/app/StreamClient.kt").readText()

    /** Nur der Code, ohne Kommentare - sonst belegt ein Kommentar sich selbst. */
    private fun codeVon(quelle: String): String =
        quelle.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun derIntentNutztDasNavigationsSchema() {
        assertTrue(
            "Ohne 'google.navigation:q=' startet keine Zielfuehrung, " +
                "sondern hoechstens eine Kartenansicht",
            codeVon(navigation).contains("google.navigation:q=")
        )
    }

    @Test
    fun dasZielWirdKodiert() {
        // "Okerstrasse 39, 12049 Berlin" ohne Kodierung = kaputter Intent.
        val code = codeVon(navigation)
        val zeile = code.lines().first { it.contains("google.navigation:q=") }
        assertTrue(
            "Das Ziel muss durch Uri.encode - ihre Adressen tragen " +
                "Leerzeichen, Kommata und Umlaute. Zeile: $zeile",
            zeile.contains("Uri.encode")
        )
    }

    @Test
    fun ausDemDienstHerausBrauchtEsNewTask() {
        assertTrue(
            "Ohne FLAG_ACTIVITY_NEW_TASK wirft Android, sobald der " +
                "Befehl aus dem Weckwort-Dienst kommt",
            codeVon(navigation).contains("FLAG_ACTIVITY_NEW_TASK")
        )
    }

    @Test
    fun erstMapsGezieltDannOffen() {
        val code = codeVon(navigation)
        val mitPaket = code.indexOf("MAPS")
        val liste = code.indexOf("listOf(MAPS")
        assertTrue("Das Maps-Paket muss benannt sein", mitPaket >= 0)
        assertTrue(
            "Erst gezielt Maps, dann ohne Paket - sonst geht bei jeder " +
                "Fahrt ein Auswahldialog auf",
            liste >= 0 && code.substring(liste).take(40).let {
                it.indexOf("MAPS") < it.indexOf("\"\"")
            }
        )
    }

    @Test
    fun einFehlschlagBleibtNichtStumm() {
        val code = codeVon(navigation)
        assertTrue(
            "Ein Fehler muss in die Statuszeile - auf dem Handy gibt es " +
                "kein lesbares Log",
            code.contains("wake_status")
        )
        assertTrue(
            "Die Fehlermeldung nennt das Ziel, sonst ist sie zur " +
                "Fehlersuche wertlos",
            code.contains("FEHLER: Navigation zu")
        )
    }

    @Test
    fun derStromLoestDieNavigationAus() {
        val code = codeVon(stream)
        assertTrue(
            "Ohne diesen Zweig kommt der Befehl zwar an, es passiert " +
                "aber nichts",
            code.contains("\"navigation\" ->")
        )
        assertTrue(
            "Der Zweig muss Navigation.starten rufen",
            code.contains("Navigation.starten")
        )
    }

    @Test
    fun einFehlerSchneidetDieAntwortNichtAb() {
        // Sie soll zu Ende hoeren, auch wenn Maps nicht aufgeht.
        val code = codeVon(stream)
        val ab = code.indexOf("\"navigation\" ->")
        assertTrue("Der Navigationszweig fehlt", ab >= 0)
        val block = code.substring(ab, minOf(ab + 700, code.length))
        assertTrue(
            "Der Aufruf gehoert in try/catch - sonst reisst ein " +
                "fehlender Maps-Handler den restlichen Strom mit",
            block.contains("try {") && block.contains("catch")
        )
    }
}
