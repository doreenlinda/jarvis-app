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

    /**
     * Schneidet den Block einer Konstanten heraus - von ihrer Zeile bis
     * zum abschliessenden build(). Eine Suche im GANZEN Quelltext taugt
     * hier nicht: USAGE_ASSISTANT steht weiterhin darin (im Fokus-Block),
     * eine contains-Pruefung bliebe also auch dann gruen, wenn das
     * Routing des Players zurueckgedreht wird.
     */
    private fun block(quelle: String, name: String): String {
        val start = quelle.indexOf("val " + name + ":")
        assertTrue("Konstante nicht gefunden: " + name, start >= 0)
        val ende = quelle.indexOf(".build()", start)
        assertTrue("build() nach " + name + " nicht gefunden", ende >= 0)
        return quelle.substring(start, ende)
    }

    /**
     * AN IHREM GERAET GEMESSEN (06.09.2026) - die Erwartung wurde
     * umgedreht, weil die Messung sie umgedreht hat, nicht um den Test
     * gruen zu bekommen:
     *
     *   v0.49 setzte USAGE_ASSISTANT auf den PLAYER. Ohne Kopfhoerer kam
     *   der Ton an, mit Bluetooth-Kopfhoerern hoerte sie GAR NICHTS -
     *   und Bluetooth ist genau der Weg, um den es ihr ging (Auto).
     *
     * Der Player traegt deshalb USAGE_MEDIA (Routing), die Absicht steht
     * im Fokus-Antrag. Wer das zurueckdreht, nimmt ihr den Ton ueber
     * Kopfhoerer und im Auto.
     */
    @Test
    fun derPlayerLaeuftUeberDenMedienwegNichtUeberAssistant() {
        val q = lies("Sprachausgabe.kt")
        val spielen = block(q, "ATTRIBUTE")
        assertTrue("Der Player muss USAGE_MEDIA nutzen - ueber Bluetooth " +
            "kommt USAGE_ASSISTANT an ihrem Geraet nicht an",
            spielen.contains("USAGE_MEDIA"))
        assertTrue("USAGE_ASSISTANT auf dem Player - genau der Fehler aus " +
            "v0.49: mit Kopfhoerern hoert sie dann nichts",
            !spielen.contains("USAGE_ASSISTANT"))
    }

    /**
     * Die Absichtserklaerung bleibt ASSISTANT - hierueber fliesst kein
     * Ton, sie beruehrt das Routing also nicht. Ohne sie waere der
     * Fokus-Antrag der einer beliebigen Medien-App.
     */
    @Test
    fun derFokusAntragBleibtAssistenz() {
        val q = lies("Sprachausgabe.kt")
        assertTrue("FOKUS_ATTRIBUTE fehlt",
            q.contains("FOKUS_ATTRIBUTE"))
        assertTrue("Der Fokus-Antrag sollte ASSISTANT bleiben",
            block(q, "FOKUS_ATTRIBUTE").contains("USAGE_ASSISTANT"))
        assertTrue("Der Fokus-Antrag nutzt nicht FOKUS_ATTRIBUTE",
            q.contains("setAudioAttributes(FOKUS_ATTRIBUTE)"))
    }

    @Test
    fun dieStimmeIstSpracheUndDuecktStattAnzuhalten() {
        val q = lies("Sprachausgabe.kt")
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
