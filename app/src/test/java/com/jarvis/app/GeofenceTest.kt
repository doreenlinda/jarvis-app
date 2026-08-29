package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ortszonen: die Entscheidung, ob eine Zone betreten oder verlassen wurde.
 *
 * WARUM DIESER TEST DER EINZIGE PRUEFWEG IST: Kotlin laesst sich auf dem
 * Laptop nicht ausfuehren - es gibt dort keine Java-Werkzeugkette. Was hier
 * nicht geprueft wird, probiert Doreen zum ersten Mal auf ihrem Handy aus,
 * und beim Weckwort-Umbau hat genau das drei Fehlversuche gekostet. Der Test
 * laeuft im Cloud-Build VOR dem Bauen; wird er rot, entsteht keine APK.
 *
 * Geprueft wird [Geofence.entscheide] - bewusst OHNE Android-Typen gebaut,
 * weil `Location.distanceTo` eine native Methode ist und im Unit-Test nicht
 * zur Verfuegung steht. Die Umrechnung von Koordinaten in einen Abstand
 * macht Android; die ENTSCHEIDUNG, was daraus folgt, macht dieser Code.
 *
 * DIE GEGENPROBEN SIND DER WICHTIGERE TEIL: Eine Zone, die zu leicht
 * ausloest, schickt ihr Meldungen ueber Wege, die sie nie gegangen ist.
 */
class GeofenceTest {

    private val R = Geofence.RADIUS_STANDARD_M          // 200
    private val P = Geofence.PUFFER_M                    // 75
    private val GUT = 20f                                // brauchbarer Fix

    // ------------------------------------------------------ Der Normalfall

    @Test
    fun betretenWirdErkannt() {
        // Sie war draussen und ist jetzt klar innerhalb des Radius.
        assertEquals("betreten", Geofence.entscheide(50f, GUT, R, "draussen"))
    }

    @Test
    fun verlassenWirdErkannt() {
        // Klar ausserhalb von Radius PLUS Puffer.
        assertEquals("verlassen",
            Geofence.entscheide((R + P + 30).toFloat(), GUT, R, "drin"))
    }

    @Test
    fun ohneWechselPassiertNichts() {
        // Sie ist drin und bleibt drin - das ist kein Ereignis.
        assertNull(Geofence.entscheide(30f, GUT, R, "drin"))
        assertNull(Geofence.entscheide(900f, GUT, R, "draussen"))
    }

    // ---------------------------------------------------------- Hysterese

    @Test
    fun imPufferRingWirdNichtsEntschieden() {
        // Genau hier springt die Ortung am haeufigsten. Ohne diesen Ring
        // entstuende eine Kette aus "verlassen / betreten / verlassen" -
        // im Postfach waere das das Rauschen, das man nach drei Tagen
        // ignoriert.
        val imRing = (R + P / 2).toFloat()
        assertNull("Im Puffer-Ring darf sich nichts aendern",
            Geofence.entscheide(imRing, GUT, R, "drin"))
        assertNull("Auch von aussen kommend nicht",
            Geofence.entscheide(imRing, GUT, R, "draussen"))
    }

    @Test
    fun knappUeberDemRadiusGiltNochNichtAlsVerlassen() {
        // Ein Meter ueber der Grenze ist kein Verlassen - dafuer gibt es
        // den Puffer.
        assertNull(Geofence.entscheide((R + 1).toFloat(), GUT, R, "drin"))
    }

    @Test
    fun exaktAufDerGrenzeIstDrin() {
        assertEquals("betreten", Geofence.entscheide(R.toFloat(), GUT, R, "draussen"))
    }

    // -------------------------------------------------------- Genauigkeit

    @Test
    fun einUngenauerFixEntscheidetGarNichts() {
        // Ein Netz-Fix in Gebaeuden kann 500 m danebenliegen. Daraus ein
        // "verlassen" abzuleiten waere schlimmer als zu schweigen.
        val zuUngenau = Geofence.GENAUIGKEIT_GRENZE_M + 1f
        assertNull(Geofence.entscheide(900f, zuUngenau, R, "drin"))
        assertNull(Geofence.entscheide(10f, zuUngenau, R, "draussen"))
    }

    @Test
    fun genauAnDerGrenzeZaehltNoch() {
        assertEquals("verlassen",
            Geofence.entscheide(900f, Geofence.GENAUIGKEIT_GRENZE_M, R, "drin"))
    }

    @Test
    fun fehlendeGenauigkeitBlockiertNicht() {
        // -1 heisst "das Geraet hat keine Angabe gemacht" - das ist etwas
        // anderes als "sehr ungenau" und darf die Zone nicht lahmlegen.
        assertEquals("betreten", Geofence.entscheide(50f, -1f, R, "draussen"))
    }

    // ------------------------------------------------------- Der erste Fix

    @Test
    fun derErsteFixMeldetNichts() {
        // Beim Anlegen der Zone steht sie per Definition mittendrin. Ein
        // sofortiges "Zuhause betreten" waere eine Meldung ueber etwas, das
        // nicht passiert ist.
        assertEquals("", Geofence.entscheide(20f, GUT, R, null))
    }

    @Test
    fun derErsteFixVonAussenMeldetEbenfallsNichts() {
        // Legt sie die Zone an und faehrt weg, bevor der erste Takt laeuft,
        // wird der Zustand ebenfalls nur festgelegt.
        assertEquals("", Geofence.entscheide(2000f, GUT, R, null))
    }

    @Test
    fun nachDemErstenFixWirdGemeldet() {
        // Der Zustand steht - ab jetzt ist jeder Wechsel ein Ereignis.
        assertEquals("verlassen", Geofence.entscheide(2000f, GUT, R, "drin"))
    }

    // ------------------------------------------------------ Zonengroessen

    @Test
    fun einGroessererRadiusWirktAuchGroesser() {
        // Ihr Wunsch nannte 500 m fuers Heimkommen - der Radius muss also
        // wirklich durchschlagen und nicht nur in der Zone gespeichert sein.
        assertNull("400 m sind in einer 500-m-Zone noch drin",
            Geofence.entscheide(400f, GUT, 500, "drin"))
        assertEquals("aber in einer 200-m-Zone laengst draussen",
            "verlassen", Geofence.entscheide(400f, GUT, 200, "drin"))
    }

    // ------------------------------------------------- Werte, die stimmen

    @Test
    fun diePufferGrenzeIstSinnvollGewaehlt() {
        // Der Puffer muss groesser sein als eine uebliche Ortungsungenauigkeit
        // in der Stadt (10-50 m) - sonst ist er wirkungslos.
        assertTrue("Puffer zu klein gegen die Ortungsstreuung", P >= 50)
        assertTrue("Puffer sollte die Zone nicht verdoppeln", P < R)
    }

    // -------------------------------------------------------- Verdrahtung
    // Die Entscheidungslogik kann fehlerfrei sein und trotzdem nie laufen.
    // Am 07.08.2026 war im Orchestrator schon einmal der Kern heil und nur
    // nicht angeschlossen; ein reiner Funktionstest haette 100 Prozent
    // gemeldet. Diese Pruefungen lesen den Quelltext - sie zeigen, dass der
    // Aufruf DASTEHT, nicht dass er wirkt. Fuer eine Verdrahtung ist das
    // vertretbar, fuer die Logik oben waere es das nicht.

    private fun dienst() =
        java.io.File("src/main/java/com/jarvis/app/WakeWordService.kt").readText()

    @Test
    fun dieZonenpruefungHaengtImVorhandenenTakt() {
        val q = dienst()
        assertTrue("Geofence.pruefen wird im Dienst nicht aufgerufen",
            q.contains("Geofence.pruefen("))
        assertTrue("Die Pruefung gehoert in den Postfach-Thread - ein eigener " +
            "Dienst waere ein zweiter Wake Lock fuer dieselbe Arbeit",
            q.indexOf("Geofence.pruefen(") > q.indexOf("postfachThread"))
    }

    @Test
    fun dieZonenWerdenSeltenerGeprueftAlsDasPostfach() {
        // Jede Pruefung fragt die Position ab und kostet Akku. Gelesen wird
        // aus dem ECHTEN Dienst, nicht aus einer Kopie im Test - sonst
        // pruefte der Test nur sich selbst.
        val q = dienst()
        val zonen = Regex("GEOFENCE_INTERVALL_MS = ([0-9_]+)L")
            .find(q)?.groupValues?.get(1)?.replace("_", "")?.toLong()
        val postfach = Regex("NACHSEHEN_INTERVALL_MS = ([0-9_]+)L")
            .find(q)?.groupValues?.get(1)?.replace("_", "")?.toLong()
        assertTrue("GEOFENCE_INTERVALL_MS nicht gefunden", zonen != null)
        assertTrue("NACHSEHEN_INTERVALL_MS nicht gefunden", postfach != null)
        assertTrue("Zonen ($zonen ms) sollen seltener geprueft werden als das " +
            "Postfach ($postfach ms)", zonen!! >= postfach!!)
    }

    @Test
    fun einZonenfehlerReisstDasLauschenNichtMit() {
        // Das Lauschen ist die Hauptaufgabe. Faellt die Ortung aus, darf der
        // Weckwort-Dienst nicht mit ihr sterben.
        val q = dienst()
        val i = q.indexOf("Geofence.pruefen(")
        assertTrue("Aufruf nicht gefunden", i > 0)
        val davor = q.substring(maxOf(0, i - 400), i)
        assertTrue("Der Aufruf steht nicht in einem try-Block",
            davor.contains("try {"))
    }

    @Test
    fun dieAppSchicktKeineKoordinatenAnDenServer() {
        // Die Zusage aus v0.31: Was den Laptop erreicht, ist ein Ortsname,
        // kein Zahlenpaar. Aus diesen Meldungen darf kein Bewegungsprofil
        // werden koennen.
        val q = java.io.File("src/main/java/com/jarvis/app/Geofence.kt").readText()
        val i = q.indexOf("FormBody.Builder()")
        assertTrue("Der Sendeteil wurde nicht gefunden", i > 0)
        val koerper = q.substring(i, minOf(q.length, i + 600))
        for (feld in listOf("lat", "lon", "latitude", "longitude")) {
            assertTrue("Der Sendeteil enthaelt $feld - es duerfen nur Zone, " +
                "Richtung und Messwerte rausgehen",
                !koerper.contains("\"" + feld + "\""))
        }
        assertTrue("Der Zonenname fehlt", koerper.contains("\"zone\""))
        assertTrue("Die Richtung fehlt", koerper.contains("\"ereignis\""))
    }
}
