package com.jarvis.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Landet Jarvis' Stimme im Nirgendwo, wird sie auf einen echten Ausgang
 * gelenkt.
 *
 * ANLASS (17.09.2026, Doreens Meldung): "Ich rufe ihn im Auto auf, hoere
 * ihn aber nicht, obwohl die Musik unterbricht."
 *
 * GEMESSEN ueber die Ton-Diagnose, gegen ihre Ortszonen gehalten:
 *
 *   zuhause (08:04, 13:48)    Ausgang  8 = BLUETOOTH_A2DP  -> hoerbar
 *   im Auto (16:58 - 17:20)   Ausgang 25 = REMOTE_SUBMIX   -> nichts
 *
 * Typ 25 ist laut Android-Quelltext "a device type for rerouting audio
 * within the Android framework between mixes and system applications" -
 * ein interner Umleitungskanal, KEIN Lautsprecher. Die App hatte dabei
 * alles richtig gemacht (gespielt=2/2, fehler=0); der Ton ging raus und
 * verschwand. Dass die Musik trotzdem duckte, passt dazu: Das haengt am
 * Fokus, und der erreicht das System weiterhin.
 *
 * WAS DIESER TEST SCHUETZT - und was er NICHT kann:
 * Er prueft die AUSWAHL (eine reine Funktion) und die Verdrahtung im
 * Quelltext. Ob Android Auto die Umleitung am Ende zulaesst, kann kein
 * Unit-Test beantworten - das entscheidet ihr naechster Zuruf im Auto,
 * und die Ton-Diagnose meldet das Ergebnis.
 */
class AusgangUmleitungTest {

    @Test
    fun nurDasNirgendwoWirdUmgeleitet() {
        assertTrue(
            "REMOTE_SUBMIX ist genau der Fall aus dem Auto",
            Sprachausgabe.brauchtUmleitung(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
        // DIE WICHTIGEN GEGENPROBEN: An einem gesunden Ausgang darf NICHTS
        // angefasst werden. v0.49 hat an dieser Stelle schon einmal den Ton
        // ueber Bluetooth komplett gekostet, weil eine Aenderung ohne Not
        // alle Faelle traf.
        assertFalse(
            "Bluetooth ist der Normalfall zuhause - hier wird nicht eingegriffen",
            Sprachausgabe.brauchtUmleitung(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(
            "Der Geraetelautsprecher ist ein echter Ausgang",
            Sprachausgabe.brauchtUmleitung(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(
            "Ein unbekannter Ausgang (-1) ist kein Grund einzugreifen",
            Sprachausgabe.brauchtUmleitung(-1))
    }

    @Test
    fun bluetoothSchlaegtDenGeraetelautsprecher() {
        // Genau ihre Lage im Auto: Der Submix ist da, Bluetooth auch.
        assertEquals(
            "Bluetooth ist der Weg ins Auto und zu den Kopfhoerern",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            Sprachausgabe.echterAusgang(intArrayOf(
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun derLautsprecherIstBesserAlsNichts() {
        assertEquals(
            "Aus dem Handy gehoert ist besser als gar nicht",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            Sprachausgabe.echterAusgang(intArrayOf(
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test
    fun aufEinenZweitenSubmixWirdNichtUmgeleitet() {
        // Steht NUR das Nirgendwo zur Wahl, wird nichts erzwungen: Eine
        // Umleitung auf denselben virtuellen Kanal waere sinnlos, und ein
        // gemeldetes "umgeleitet" waere dann sogar irrefuehrend.
        assertEquals(
            -1,
            Sprachausgabe.echterAusgang(intArrayOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)))
        assertEquals(
            "Ohne jeden Ausgang gibt es nichts zu waehlen",
            -1,
            Sprachausgabe.echterAusgang(intArrayOf()))
    }

    @Test
    fun derAutoBusZaehltAlsEchterAusgang() {
        assertEquals(
            "Fest verbautes Auto-Audio ist ein echter Ausgang",
            AudioDeviceInfo.TYPE_BUS,
            Sprachausgabe.echterAusgang(intArrayOf(
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_BUS)))
    }

    @Test
    fun beideAntwortWegeSindVerdrahtet() {
        // OHNE DIESE PRUEFUNG waere der Kern heil und der Anschluss fehlte -
        // genau das ist am 07.08.2026 beim Entwurfspfad passiert, wo ein
        // reiner Funktionstest 100 % meldete, waehrend der Zuruf ins Leere
        // lief. Beide Wege muessen dran sein: die satzweise Antwort
        // (StreamClient) und die Antwort im Weckwort-Dienst.
        for (datei in listOf("StreamClient.kt", "WakeWordService.kt")) {
            val q = File("src/main/java/com/jarvis/app/$datei").readText()
            assertTrue(
                "$datei leitet den Ton nicht um - im Auto bliebe er unhoerbar",
                q.contains("Sprachausgabe.umleitenWennNoetig"))
            // Die Umleitung MUSS zwischen prepare() und start() sitzen:
            // davor kennt der Player seinen Ausgang noch nicht, danach
            // laeuft er bereits.
            val vorStart = q.substringBefore("Sprachausgabe.umleitenWennNoetig")
            assertTrue(
                "$datei: die Umleitung steht nicht nach prepare()",
                vorStart.contains("prepare()"))
        }
    }

    @Test
    fun dieUmleitungWirdGemeldet() {
        // Sonst ist ein normaler Ausgang im Protokoll nicht mehr deutbar:
        // kam er von selbst, oder haben wir ihn erzwungen? Das ist die eine
        // Frage, die ihr naechster Zuruf im Auto beantworten soll.
        for (datei in listOf("StreamClient.kt", "WakeWordService.kt")) {
            val q = File("src/main/java/com/jarvis/app/$datei").readText()
            assertTrue(
                "$datei meldet die Umleitung nicht an die Ton-Diagnose",
                q.contains("probe.umgeleitet("))
        }
        val probe = File("src/main/java/com/jarvis/app/TonProbe.kt").readText()
        assertTrue(
            "TonProbe schickt das Feld nicht mit",
            probe.contains("\"umgeleitet\""))
        assertTrue(
            "TonProbe schickt die vorhandenen Ausgaenge nicht mit - dann " +
                "waere nicht erkennbar, ob ueberhaupt einer zur Wahl stand",
            probe.contains("\"vorhanden\""))
    }
}
