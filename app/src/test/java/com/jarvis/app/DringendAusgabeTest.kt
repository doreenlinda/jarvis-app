package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueft, WAS bei einer dringenden Meldung hoerbar wird (v0.33).
 *
 * ANLASS (Doreen, 05.08.2026): "Ich wuerde ungern wollen, dass in einem
 * Kundengespraech ploetzlich Jarvis' Mitteilung laut zu hoeren ist, da das
 * ja auch eine sehr persoenliche Notsituation sein kann."
 *
 * Der teure Fehler ist eindeutig gerichtet: eine laut vorgelesene Notlage
 * vor fremden Ohren. Ein Satz zu wenig ist dagegen harmlos. Deshalb steht
 * hier vor allem, dass ohne ausdrueckliche Freigabe NIE der Inhalt kommt.
 *
 * Warum als Unit-Test: Der Laptop hat keine Java-Werkzeugkette, Kotlin
 * laeuft nur im Cloud-Build. Der Test liegt VOR dem Bauen - stimmt die
 * Entscheidung nicht, entsteht gar keine APK.
 */
class DringendAusgabeTest {

    @Test
    fun ohneFreigabeNiemalsDerInhalt() {
        // Der Kern der Zusage. Auch wenn eine Tondatei vorliegt.
        assertEquals(
            Ausgabe.HINWEIS,
            DringendAusgabe.waehle(inhaltVorlesen = false, hatInhaltsTon = true)
        )
        assertEquals(
            Ausgabe.HINWEIS,
            DringendAusgabe.waehle(inhaltVorlesen = false, hatInhaltsTon = false)
        )
    }

    @Test
    fun mitFreigabeUndTonKommtDerInhalt() {
        assertEquals(
            Ausgabe.INHALT,
            DringendAusgabe.waehle(inhaltVorlesen = true, hatInhaltsTon = true)
        )
    }

    @Test
    fun mitFreigabeAberOhneTonBleibtDerHinweis() {
        // Sonst bliebe nur die Vibration - ein neutraler Satz ist besser als
        // Stille, und verraet nichts.
        assertEquals(
            Ausgabe.HINWEIS,
            DringendAusgabe.waehle(inhaltVorlesen = true, hatInhaltsTon = false)
        )
    }
}
