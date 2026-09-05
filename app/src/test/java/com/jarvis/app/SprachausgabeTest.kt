package com.jarvis.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jarvis' Stimme schiebt sich vor die Musik - und der Wecker bleibt Wecker.
 *
 * ANLASS (05.09.2026, Doreens Meldung): "Wichtige Meldungen von Jarvis
 * sollen in den Vordergrund treten, die Musik also automatisch leiser
 * werden. Aktuell hoere ich ihn auch nur ueber Handy." Ihr Handy haengt
 * per Bluetooth am Auto.
 *
 * Gemessen war die Ursache: Die normalen Antworten liefen ueber einen
 * nackten MediaPlayer - ohne Audio-Attribute und ohne Fokus-Antrag. Fuer
 * Android war die Stimme damit MUSIK, und ohne Fokus weiss das System
 * nicht, dass gerade jemand spricht.
 *
 * WARUM ES DIESEN TEST GIBT - drei Gefahren, die ohne ihn unsichtbar sind:
 *
 *   1. Die Ausgabe verteilt sich auf FUENF Stellen in drei Dateien. Eine
 *      spaeter hinzugefuegte bleibt sonst stillschweigend "Musik", und
 *      auffallen wuerde es Doreen - im Auto, nach einem Sideload.
 *   2. Ein angeforderter Fokus, der nie zurueckgegeben wird, laesst die
 *      Musik DAUERHAFT leise. Das ist schlimmer als gar kein Ducking.
 *   3. Der Wecker-Ausgang der dringenden Meldungen (USAGE_ALARM) ist seit
 *      v0.32 bewusst so gebaut, damit der Aufbruch-Alarm auch bei stummem
 *      Handy klingt - und ihr Handy ist dauerhaft stumm. Wer ihn auf
 *      USAGE_ASSISTANT "vereinheitlicht", macht ihn zu Hause unhoerbar.
 *      Diese Gegenprobe ist die wichtigste des Tests.
 *
 * EHRLICHE GRENZE: Der Test liest QUELLTEXT. Er belegt, dass die Angaben
 * dastehen - nicht, wie es im Auto klingt. Ob die Musik wirklich leiser
 * wird, entscheidet Doreens Ohr; und gedueckt werden kann ohnehin nur,
 * was das HANDY abspielt.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class SprachausgabeTest {

    private fun lies(name: String): String {
        val datei = File("src/main/java/com/jarvis/app/$name")
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText()
    }

    @Test
    fun dieStimmeIstAssistenzUndDuecktStattAnzuhalten() {
        val q = lies("Sprachausgabe.kt")
        assertTrue("USAGE_ASSISTANT fehlt - die Stimme gaelte wieder als Musik",
            q.contains("USAGE_ASSISTANT"))
        assertTrue("CONTENT_TYPE_SPEECH fehlt",
            q.contains("CONTENT_TYPE_SPEECH"))
        // MAY_DUCK laesst die Musik leiser werden. Ohne den Zusatz wuerde
        // sie ANGEHALTEN - bei einem Zuruf von zwei Sekunden waere das die
        // laestigere Variante.
        assertTrue("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK fehlt",
            q.contains("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"))
    }

    @Test
    fun jedeNormaleAusgabeIstAngeschlossen() {
        // Fuenf Stellen spielen Jarvis' Stimme ab. Jede muss die Attribute
        // setzen, sonst gilt sie als Musik.
        val stream = lies("StreamClient.kt")
        val dienst = lies("WakeWordService.kt")
        val haupt = lies("MainActivity.kt")

        assertTrue("StreamClient (der HAUPTweg) setzt die Attribute nicht",
            stream.contains("setAudioAttributes(Sprachausgabe.ATTRIBUTE)"))
        assertTrue("Der /assistant-Rueckfall im Dienst setzt sie nicht",
            dienst.contains("setAudioAttributes(Sprachausgabe.ATTRIBUTE)"))
        assertTrue("MainActivity setzt sie nicht",
            haupt.contains("setAudioAttributes(Sprachausgabe.ATTRIBUTE)"))

        // Und jede Datei, die Ton ausgibt, fordert den Fokus an.
        for ((name, q) in listOf(
            "StreamClient.kt" to stream,
            "WakeWordService.kt" to dienst,
            "MainActivity.kt" to haupt,
        )) {
            assertTrue("$name fordert keinen Fokus an - die Musik bliebe laut",
                q.contains("Sprachausgabe.fokusAnfordern"))
        }
    }

    @Test
    fun einAngeforderterFokusWirdAuchZurueckgegeben() {
        for (name in listOf("StreamClient.kt", "WakeWordService.kt",
                            "MainActivity.kt")) {
            val q = lies(name)
            val anfordern = Regex("fokusAnfordern").findAll(q).count()
            val freigeben = Regex("fokusFreigeben").findAll(q).count()
            assertTrue(
                "$name: $anfordern Anforderungen, aber nur $freigeben " +
                    "Freigaben - ein haengender Fokus laesst die Musik " +
                    "dauerhaft leise",
                freigeben >= anfordern)
        }
    }

    @Test
    fun derFokusHaeltUeberDieGANZEAntwort() {
        // Bei satzweisen Antworten (Normalfall seit dem 24.07.2026) darf der
        // Fokus NICHT je Sprechblock zurueckgegeben werden - die Musik wuerde
        // sonst zwischen jedem Satz laut und wieder leise.
        val q = lies("StreamClient.kt")
        val nachFertig = q.substringAfter("if (stromFertig)")
            .substringBefore("return")
        assertTrue(
            "Die Freigabe haengt nicht am Ende des STROMS - damit koennte " +
                "sie je Block feuern und die Musik wuerde pumpen",
            nachFertig.contains("fokusFreigeben"))
    }

    @Test
    fun derWeckerBleibtWecker() {
        // DIE WICHTIGSTE GEGENPROBE: Der Aufbruch-Alarm und das
        // WhatsApp-Klingeln muessen bei stummem Handy hoerbar bleiben.
        val q = lies("WakeWordService.kt")
        assertTrue(
            "Der Wecker-Ausgang ist weg - dringende Meldungen waeren bei " +
                "ihrem (dauerhaft stummen) Handy nicht mehr zu hoeren",
            q.contains("USAGE_ALARM"))
        assertTrue("weckerAusgang() nutzt nicht mehr USAGE_ALARM",
            q.substringAfter("private fun weckerAusgang()")
                .take(200).contains("USAGE_ALARM"))
        // Die dringende Meldung soll die Musik trotzdem ducken.
        assertTrue(
            "Die dringende Meldung fordert keinen Fokus an - genau sie soll " +
                "aber in den Vordergrund treten",
            q.substringAfter("private fun spieleDatei(")
                .take(400).contains("fokusAnfordern"))
    }
}
