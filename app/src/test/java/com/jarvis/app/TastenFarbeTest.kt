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
     * Haelt Doreens Entscheidung fest.
     *
     * GEAENDERT AM 18.08.2026, NACHMITTAGS - und zwar von ihr selbst, nicht
     * durch Aufweichen dieses Tests: Mit dem Umbau des Voice Orbs auf eine
     * Glaskugel wurde die ganze App fest dunkel (Schema 1 aus einer Vorschau
     * mit drei Abstufungen). Ihre Vorgabe: "Der Hintergrund und Tasten
     * muessen auch so dunkel. Die Schrift, auf den Tasten nicht weiss, der
     * Kontrast waere zu stark zu den dunkleren Tasten."
     *
     * Vorher galt hier #3A3A3A mit weisser Schrift #E8E8E8 - festgehalten
     * am Vormittag desselben Tages, als die App noch dem Hell-/Dunkelmodus
     * des Handys folgte. Diese Begruendung ist mit dem festen dunklen Thema
     * entfallen.
     *
     * Wer diese Werte aendert, aendert eine getroffene Entscheidung - und
     * soll darueber stolpern. Fuer die Lesbarkeit gilt: Die Kontraste sind
     * gemessen (6,5:1 auf der Taste), nicht geschaetzt; siehe colors.xml.
     */
    @Test
    fun graphitIstDerGewaehlteTon() {
        val farben = lies("src/main/res/values/colors.xml")
        assertTrue(
            "Der von Doreen gewaehlte Ton #1C1917 steht nicht mehr in colors.xml",
            farben.contains("<color name=\"taste_graphit\">#1C1917</color>")
        )
        assertTrue(
            "Die gedaempfte Schriftfarbe #A79C93 fehlt - weiss war ihr zu grell",
            farben.contains("<color name=\"taste_schrift\">#A79C93</color>")
        )
        assertTrue(
            "Der dunkle App-Grund fehlt - der Orb soll die hellste Stelle im Bild sein",
            farben.contains("<color name=\"app_grund\">#0B0A0A</color>")
        )
    }

    /**
     * Die App folgt NICHT mehr dem Hell-/Dunkelmodus des Handys. Der Orb ist
     * eine Glaskugel auf schwarzem Grund; im hellen Systemmodus saesse sie
     * als einziges dunkles Feld in einer weissen App.
     *
     * Ohne diesen Test faellt ein versehentliches Zurueckdrehen auf DayNight
     * erst Doreen auf - und zwar draussen bei Tageslicht, wenn ihr Handy von
     * selbst in den hellen Modus wechselt.
     */
    @Test
    fun dieAppIstFestDunkel() {
        val manifest = lies("src/main/AndroidManifest.xml")
        assertTrue(
            "Das Manifest verweist nicht auf Theme.Jarvis",
            manifest.contains("android:theme=\"@style/Theme.Jarvis\"")
        )
        assertTrue(
            "DayNight ist zurueck - die App wuerde im hellen Systemmodus hell",
            !manifest.contains("DayNight")
        )
        val thema = lies("src/main/res/values/themes.xml")
        assertTrue(
            "Theme.Jarvis setzt keinen dunklen Fensterhintergrund",
            thema.contains("<item name=\"android:windowBackground\">@color/app_grund</item>")
        )
    }

    /** Jede Taste im Layout traegt Hintergrund UND Schriftfarbe. */
    @Test
    fun jedeTasteImLayoutIstGefaerbt() {
        val layout = lies("src/main/res/layout/activity_main.xml")
        val tasten = layout.split("<Button").drop(1)
        // 6 seit v0.42: Der Einstellungs-Knopf ist dazugekommen, der die drei
        // Schalter einklappt (Doreens Wunsch, 18.08.2026). Testdaten bewusst
        // angepasst - die Pruefung selbst bleibt: JEDE Taste traegt den Anstrich.
        assertEquals("Anzahl der Tasten im Layout hat sich geaendert", 6, tasten.size)
        tasten.forEach { roh ->
            val block = roh.substringBefore("/>")
            // Bewusst ohne regulaeren Ausdruck: Der Name dient nur der
            // Fehlermeldung, und einfache Zeichenkettenschnitte sind hier
            // weniger fehleranfaellig als Maskierungen.
            val name = block.substringAfter("@+id/", "unbenannt").substringBefore("\"")
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
        val erzeugt = quelle.split("Button(this").size - 1
        assertEquals("Anzahl der zur Laufzeit erzeugten Tasten hat sich geaendert", 3, erzeugt)
        assertEquals(
            "Nicht jede zur Laufzeit erzeugte Taste ruft graphit()",
            erzeugt,
            quelle.lines().count { it.trim() == "graphit()" }
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
            grafik.split("<padding").size - 1
        )
        assertTrue(
            "Der Gedrueckt-Zustand fehlt - die Taste fuehlte sich beim Antippen tot an",
            grafik.contains("android:state_pressed=\"true\"")
        )
    }
}
