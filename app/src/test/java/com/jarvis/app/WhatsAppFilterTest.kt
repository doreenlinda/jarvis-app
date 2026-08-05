package com.jarvis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft, welche Benachrichtigungen ueberhaupt an Jarvis gehen (v0.32).
 *
 * Warum hier: Der Laptop hat keine Java-Werkzeugkette - Kotlin laeuft
 * ausschliesslich im Cloud-Build. Dieser Test liegt deshalb, genau wie
 * KryptoFormatTest und StandortFormatTest, VOR dem Bauen im Workflow.
 *
 * Was auf dem Spiel steht: Jede durchgelassene Benachrichtigung kostet auf
 * dem Server einen Modellaufruf, und im schlimmsten Fall laesst sie Doreens
 * Handy klingeln. Umgekehrt ist eine faelschlich verworfene Nachricht eine
 * verpasste Terminabsage. Deshalb stehen hier beide Richtungen.
 */
class WhatsAppFilterTest {

    private fun w(
        paket: String = "com.whatsapp",
        titel: String = "Bettina",
        text: String = "Ich muss morgen leider absagen.",
        sammel: Boolean = false,
        dauerhaft: Boolean = false,
    ) = WhatsAppFilter.weiterleiten(paket, titel, text, sammel, dauerhaft)

    @Test
    fun normaleNachrichtGehtDurch() {
        assertTrue(w())
    }

    @Test
    fun whatsappBusinessGehtAuchDurch() {
        assertTrue(w(paket = "com.whatsapp.w4b"))
    }

    @Test
    fun andereAppsWerdenNiemalsWeitergeleitet() {
        // Der Kern der Zusage an Doreen: Die Berechtigung erlaubt technisch
        // ALLES, beschraenkt wird hier.
        assertFalse(w(paket = "com.android.email"))
        assertFalse(w(paket = "org.telegram.messenger"))
        assertFalse(w(paket = "com.samsung.android.messaging"))
        assertFalse(w(paket = ""))
    }

    @Test
    fun sammelmeldungUndDauerhafteMeldungFallenWeg() {
        assertFalse(w(sammel = true))
        assertFalse(w(dauerhaft = true))
    }

    @Test
    fun systemmeldungenSindKeineNachrichten() {
        assertFalse(w(titel = "WhatsApp", text = "Nach neuen Nachrichten suchen..."))
        assertFalse(w(titel = "WhatsApp", text = "3 neue Nachrichten von 2 Chats"))
        assertFalse(w(titel = "Bettina", text = "Verpasster Anruf"))
        assertFalse(w(titel = "Bettina", text = "Eingehender Anruf"))
    }

    @Test
    fun leereFelderFallenWeg() {
        assertFalse(w(titel = ""))
        assertFalse(w(text = ""))
        assertFalse(w(titel = "   ", text = "   "))
    }

    @Test
    fun doppelpunktImSatzIstKeinGrundZumVerwerfen() {
        // Der Serverentwurf vom 05.08.2026 hielt alles fuer eine Gruppe, was
        // mit "Wort: " beginnt - und haette damit genau diese echte
        // Kundennachricht still verschluckt. Ob es eine Gruppe ist, meldet
        // Android getrennt; hier wird nichts aus dem Text geraten.
        assertTrue(w(text = "Kurze Frage: passt Donnerstag auch?"))
        assertTrue(w(titel = "Marjam", text = "Guten Morgen: kurze Info vorab."))
    }

    @Test
    fun echteAbsageMitAnrufWortImSatzGehtDurch() {
        // Gegenprobe zum Anruf-Muster: Es darf nur greifen, wenn es die
        // GANZE Meldung ist - sonst faellt eine echte Nachricht heraus.
        assertTrue(w(text = "Ich hatte einen Anruf, ich muss morgen absagen."))
        assertTrue(w(text = "Ich rufe an, sobald ich mehr weiss."))
    }
}
