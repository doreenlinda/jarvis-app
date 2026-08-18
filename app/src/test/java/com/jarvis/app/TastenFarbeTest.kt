package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Alle Tasten der App tragen dasselbe Graphit (Doreens Wahl vom 18.08.2026,
 * Variante B aus einer Vorschau mit drei Toenen).
 *
 * WARUM ES DIESEN TEST GIBT: Der Anstrich verteilt sich auf drei Stellen -
 * das Layout, die Farbdatei und die Tasten des Postfachs, die erst zur
 * Laufzeit entstehen. Eine spaeter hinzugefuegte Taste bleibt sonst
 * stillschweigend hellgrau, und heraus kaeme der halbe Anstrich, der
 * schlimmer aussieht als gar keiner. Auffallen wuerde das erst Doreen, nach
 * einem Sideload.
 *
 * EHRLICHE GRENZE: Dieser Test liest QUELLTEXT. Er belegt, dass die Angaben
 * dastehen - nicht, wie es auf dem Handy aussieht. Bei einem Layout-Attribut
 * ist das vertretbar (aapt wendet es an), bei Verhalten waere es das nicht.
 * Wie die Farbe WIRKT, entscheidet weiterhin ihr Auge.
 *
 * Der Arbeitsordner der Unit-Tests ist der Modulordner (app/).
 */
class TastenFarbeTest {

    private fun lies(pfad: String): String {
        val datei = File(pfad)
        assertTrue("Datei nicht gefunden: " + datei.absolutePath, datei.exists())
        return datei.readText()
    }

    /**
     * Haelt Doreens Entscheidung fest. Vorgelegt waren #2E2E2E, #3A3A3A und
     * #1F1F1F; sie hat sich fuer den mittleren Ton entschieden, weil die App
     * dem Hell-/Dunkelmodus des Handys folgt und der dunkelste Ton dort mit
     * dem Hintergrund verschwimmen kann. Wer den Wert aendert, aendert eine
     * getroffene Entscheidung - und soll darueber stolpern.
     */
    @Test
    fun graphitIstDerGewaehlteTon() {
        val farben = lies("src/main/res/values/colors.xml")
        assertTrue(
            "Der von Doreen gewaehlte Ton #3A3A3A steht nicht mehr in colors.xml",
            farben.contains("<color name=\"taste_graphit\">#3A3A3A</color>")
        )
        assertTrue(
            "Die helle Schriftfarbe fehlt - auf Graphit waere dunkle Schrift kaum lesbar",
            farben.contains("<color name=\"taste_schrift\">#E8E8E8</color>")
        )
    }

    /** Jede Taste im Layout traegt Hintergrund UND Schriftfarbe. */
    @Test
    fun jedeTasteImLayoutIstGefaerbt() {
        val layout = lies("src/main/res/layout/activity_main.xml")
        val tasten = layout.split("<Button").drop(1)
        assertEquals("Anzahl der Tasten im Layout hat sich geaendert", 5, tasten.size)
        tasten.forEach { roh ->
            val block = roh.substringBefore("/>")
            val name = Regex("@\+id/(\w+)").find(block)?.groupValues?.get(1) ?: "unbenannt"
            assertTrue(
                "Taste '" + name + "' hat keinen Graphit-Hintergrund",
                block.contains("android:background=\"@drawable/taste_graphit\"")
            )
            assertTrue(
                "Taste '" + name + "' hat keine helle Schriftfarbe",
                block.contains("android:textColor=\"@color/taste_schrift\"")
            )
        }
    }

    /**
     * Die drei Tasten des Postfachs entstehen erst beim Anzeigen und koennen
     * deshalb nichts aus dem Layout erben - sie muessen graphit() rufen.
     */
    @Test
    fun auchDieTastenDesPostfachsSindGefaerbt() {
        val quelle = lies("src/main/java/com/jarvis/app/MainActivity.kt")
        val erzeugt = Regex("Button\(this(@MainActivity)?\)\.apply")
            .findAll(quelle).count()
        assertEquals("Anzahl der zur Laufzeit erzeugten Tasten hat sich geaendert", 3, erzeugt)
        assertEquals(
            "Nicht jede zur Laufzeit erzeugte Taste ruft graphit()",
            erzeugt,
            Regex("^\s*graphit\(\)$", RegexOption.MULTILINE).findAll(quelle).count()
        )
    }

    /**
     * Die eigene Hintergrundgrafik bringt den Innenabstand der Standard-Taste
     * nicht mit. Fehlt er, klebt die Schrift am Rand - und zwar genau bei den
     * Tasten, die im Layout keinen eigenen Abstand setzen.
     */
    @Test
    fun dieGrafikBringtIhrenEigenenInnenabstandMit() {
        val grafik = lies("src/main/res/drawable/taste_graphit.xml")
        assertEquals(
            "Nicht jeder Zustand der Grafik hat einen Innenabstand",
            2,
            Regex("<padding").findAll(grafik).count()
        )
        assertTrue(
            "Der Gedrueckt-Zustand fehlt - die Taste fuehlte sich beim Antippen tot an",
            grafik.contains("android:state_pressed=\"true\"")
        )
    }
}
