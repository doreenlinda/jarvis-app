package com.jarvis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt Doreens Entscheidungen zum Voice Orb fest (03.09.2026).
 *
 * WARUM ALS QUELLTEXT-PRUEFUNG: Wie die Kugel AUSSIEHT, kann kein Test
 * beurteilen - das entscheidet ihr Auge, und es hat drei Anlaeufe
 * gebraucht. Was ein Test kann, ist verhindern, dass eine getroffene Wahl
 * still zurueckgedreht wird. Dasselbe Vorgehen wie beim TastenFarbeTest.
 *
 * Die Vorgeschichte steht im Kopf von VoiceOrbView.kt; kurz:
 * - Anlauf 1 (Ellipsen durch die Kugel) -> "Planeten auf Umlaufbahnen"
 * - Anlauf 2 (Punkte, Sterne, festes Spitzlicht) -> "Saturn mit Umlaufbahn"
 * - gewaehlt: Saum aus wandernden Lichtern, Orange-Stufe C
 */
class OrbGlasTest {

    private fun orb(): String =
        File("src/main/java/com/jarvis/app/VoiceOrbView.kt").readText()

    @Test
    fun derAufhelltonIstOrangeUndNichtCremig() {
        val q = orb()
        assertTrue(
            "Der Aufhellton ist nicht mehr 255,182,120 - Doreen hat am " +
                "03.09.2026 ausdruecklich mehr Orange und weniger Gelb gewaehlt",
            q.contains("255, 182, 120"),
        )
        assertFalse(
            "Der cremige Ton 255,222,190 ist zurueck - genau der hat den " +
                "gelben Eindruck gemacht, den sie beanstandet hat",
            q.contains("255, 222, 190"),
        )
    }

    @Test
    fun dieSchwellenBleibenBeiStufeC() {
        val q = orb()
        assertTrue(
            "Die obere Schwelle ist nicht mehr 0.90 - dann hellt der Saum " +
                "haeufiger auf, und aus Stufe C wuerde wieder A oder B",
            q.contains("w > 0.90f"),
        )
        assertTrue(
            "Die untere Schwelle ist nicht mehr 0.68 - der Saum soll " +
                "ueberwiegend im kraeftigen Orange bleiben",
            q.contains("w > 0.68f"),
        )
    }

    @Test
    fun keinFestesSpitzlicht() {
        val q = orb()
        assertFalse(
            "Es gibt wieder ein festes Spitzlicht. In Anlauf 2 war genau " +
                "das (zusammen mit dem Randsaum) der Grund fuer ihr Urteil " +
                "-sieht aus wie der Planet Saturn mit Umlaufbahn-",
            q.contains("fun spitzlicht"),
        )
    }

    @Test
    fun dreiLichterMitVerschiedenenTempi() {
        val q = orb()
        // Der Kern der Fassung: gleiche Tempi ergaeben wieder ein starres
        // Muster, das rotiert - und ein rotierendes starres Muster ist der
        // Reifen, den sie zweimal beanstandet hat.
        assertTrue("Das zweite Licht laeuft nicht mehr gegenlaeufig", q.contains("-v * 0.62f"))
        assertTrue("Das dritte Licht hat nicht mehr sein eigenes Tempo", q.contains("v * 1.73f"))
    }

    @Test
    fun derSaumIstNirgendsGeschlossen() {
        // Der Grundwert 0.06 ist bewusst niedrig: Der Saum soll Stellen
        // haben, an denen er fast verschwindet. Ein gleichmaessig heller
        // Ring rundherum ist ein Reifen.
        assertTrue(
            "Der Grundwert des Saums wurde angehoben - dann schliesst er " +
                "sich zum durchgehenden Ring",
            orb().contains("var w = 0.06f"),
        )
    }

    @Test
    fun dieAufloesungPasstZurSchmalstenGlocke() {
        // 72 Stufen sind 5 Grad. Die schmalste Glocke ist 0.34 rad breit
        // (rund 19 Grad) - deutlich weniger, und sie wuerde eckig.
        assertTrue(
            "Die Aufloesung des Saums hat sich geaendert - unter rund 60 " +
                "Stufen wird die schmalste Glocke eckig",
            orb().contains("const val STUFEN = 72"),
        )
    }
}
