package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Der Voice Orb zeigt den richtigen Zustand.
 *
 * WARUM DIESER TEST WICHTIG IST: Kotlin laesst sich auf Doreens Rechner
 * nicht ausfuehren - vor dem Ausrollen ist nur pruefbar, was eine reine
 * Funktion ist. Genau deshalb ist die Zuordnung "Statustext -> Zustand"
 * aus der View herausgeloest. Er laeuft im Cloud-Build VOR dem Bauen; bei
 * einem Fehlschlag entsteht gar keine APK.
 *
 * Die STATUSTEXTE hier sind woertlich die aus WakeWordService.meldeStatus().
 * Wird dort einer umformuliert, faellt es hier auf - sonst zeigte der Orb
 * stillschweigend den falschen Zustand.
 */
class OrbZustandTest {

    private fun zustand(
        status: String,
        lebt: Boolean = true,
        sprichtSeit: Long = 0L,
        jetzt: Long = 1_000_000L,
    ) = OrbZustand.ausStatus(status, lebt, sprichtSeit, jetzt)

    @Test
    fun dienstAusSchlaegtAlles() {
        assertEquals(OrbZustand.AUS, zustand("Lauscht … Wert 0,31", lebt = false))
        assertEquals(
            OrbZustand.AUS,
            zustand("Weckwort erkannt – ich höre Ihre Frage …", lebt = false),
        )
        // Auch waehrend des Sprechens: Meldet sich der Dienst nicht mehr,
        // ist der Zeitstempel wertlos.
        assertEquals(
            OrbZustand.AUS,
            zustand("Frage gesendet – Antwort läuft …", lebt = false,
                    sprichtSeit = 999_000L),
        )
    }

    @Test
    fun fehlerSchlaegtAllesAndere() {
        assertEquals(
            OrbZustand.FEHLER,
            zustand("FEHLER: Mikrofon nicht verfügbar (Berechtigung? Andere App?)"),
        )
        assertEquals(OrbZustand.FEHLER, zustand("FEHLER im Selbsttest: irgendwas"))
        assertEquals(
            OrbZustand.FEHLER,
            zustand("FEHLER bei der Aufnahme: x", sprichtSeit = 999_000L),
        )
    }

    @Test
    fun ruheIstDasStilleWartenAufDasWeckwort() {
        // Das ist der Normalfall - er muss RUHE sein und nicht "lauschen":
        // Lauschen heisst hier, dass Jarvis IHRE Frage aufnimmt.
        assertEquals(OrbZustand.RUHE, zustand("Lauscht … Wert 0,31 (Mikro 0,04)"))
        assertEquals(OrbZustand.RUHE, zustand("Keine Frage gehört – ich lausche weiter."))
        assertEquals(OrbZustand.RUHE, zustand("Nichts verstanden (Nachfass) – ich lausche weiter."))
        assertEquals(OrbZustand.RUHE, zustand("Dienst gestartet, lade Erkennungsmodelle …"))
        assertEquals(OrbZustand.RUHE, zustand(""))
    }

    @Test
    fun lauschenIstDieAufnahmeIhrerFrage() {
        assertEquals(OrbZustand.LAUSCHEN, zustand("Weckwort erkannt – ich höre Ihre Frage …"))
        assertEquals(OrbZustand.LAUSCHEN, zustand("Ich höre noch – sprechen Sie einfach weiter."))
    }

    @Test
    fun denkenIstDieWartezeitAufDieAntwort() {
        assertEquals(OrbZustand.DENKEN, zustand("Frage aufgenommen, sende an Jarvis …"))
        assertEquals(OrbZustand.DENKEN, zustand("Frage gesendet – Antwort läuft …"))
        assertEquals(OrbZustand.DENKEN, zustand("Stream fehlgeschlagen, versuche klassisch …"))
    }

    @Test
    fun sprechenSchlaegtDenken() {
        // DER FALL, DER DIE GANZE KONSTRUKTION BEGRUENDET: Waehrend Jarvis
        // spricht, steht im Statustext weiterhin "Antwort läuft …". Ohne
        // den Vorrang bliebe der Orb die ganze Antwort ueber im
        // Denk-Zustand - also genau dann falsch, wenn Doreen hinsieht.
        assertEquals(
            OrbZustand.SPRECHEN,
            zustand("Frage gesendet – Antwort läuft …",
                    sprichtSeit = 990_000L, jetzt = 1_000_000L),
        )
    }

    @Test
    fun abgelaufenerSprechStempelHaeltNichtEwig() {
        // Wird die App waehrend des Sprechens beendet, bleibt der
        // Zeitstempel stehen. Danach darf der Orb nicht dauerhaft
        // "spricht" zeigen.
        val alt = 1_000_000L - OrbZustand.SPRECH_HOECHSTDAUER_MS - 1
        assertEquals(
            OrbZustand.DENKEN,
            zustand("Frage gesendet – Antwort läuft …", sprichtSeit = alt),
        )
        assertEquals(OrbZustand.RUHE, zustand("Lauscht … Wert 0,20", sprichtSeit = alt))
    }

    @Test
    fun stempelAusDerZukunftWirdNichtGeglaubt() {
        // Eine verstellte Uhr darf den Orb nicht einfrieren.
        assertEquals(
            OrbZustand.DENKEN,
            zustand("Frage gesendet – Antwort läuft …",
                    sprichtSeit = 2_000_000L, jetzt = 1_000_000L),
        )
    }

    @Test
    fun kennerDerGrenzen() {
        // Genau an der Grenze gilt der Stempel noch.
        assertEquals(
            OrbZustand.SPRECHEN,
            zustand("Frage gesendet – Antwort läuft …",
                    sprichtSeit = 1_000_000L - OrbZustand.SPRECH_HOECHSTDAUER_MS),
        )
    }
}
