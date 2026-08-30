package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ------------------------------------------------ Zonen zusammenfuehren

    @Test
    fun dieEigeneZoneGewinntGegenDieVomServer() {
        // WARUM SO HERUM: Eine Zone, bei der Doreen WIRKLICH stand, sitzt
        // genauer als jeder Geocoder. Ihr getipptes "Zuhause" darf nicht
        // von der serverseitig geocodierten Fassung verdraengt werden.
        val eigene = listOf(Geofence.Zone("Zuhause", 52.41, 13.42, 200))
        val server = listOf(Geofence.Zone("Zuhause", 52.99, 13.99, 200),
                            Geofence.Zone("Ruth", 52.44, 13.32, 100))
        val alle = Geofence.zusammenfuehren(eigene, server)
        assertEquals(2, alle.size)
        assertEquals(52.41, alle.first { it.name == "Zuhause" }.lat, 0.0001)
    }

    @Test
    fun grossKleinUndLeerzeichenErzeugenKeineDoppelte() {
        // Sonst gaebe es zwei Zonen am selben Ort, und jedes Ereignis
        // wuerde doppelt gemessen - in einer Reihe, aus der Fahrzeiten
        // werden sollen.
        val eigene = listOf(Geofence.Zone(" zuhause ", 52.41, 13.42, 200))
        val server = listOf(Geofence.Zone("Zuhause", 52.99, 13.99, 200))
        assertEquals(1, Geofence.zusammenfuehren(eigene, server).size)
    }

    @Test
    fun ohneEigeneKommenAlleVomServer() {
        val server = listOf(Geofence.Zone("Ruth", 52.44, 13.32, 100),
                            Geofence.Zone("Anja", 52.47, 13.43, 100))
        assertEquals(2, Geofence.zusammenfuehren(emptyList(), server).size)
    }

    // ------------------------------------------------------ Liste lesen

    @Test
    fun eineKaputteListeErgibtKeineZonen() {
        // Sie darf den Dienst nicht mitreissen - dann gibt es eben keine
        // Zonen, und das Lauschen laeuft weiter.
        assertEquals(0, Geofence.ausJson("{kein json").size)
        assertEquals(0, Geofence.ausJson("").size)
    }

    @Test
    fun einEintragOhneNamenWirdUebersprungen() {
        // Er waere spaeter nicht zuzuordnen - eine namenlose Zone kann
        // nichts melden.
        val roh = """[{"name":"Ruth","lat":52.4,"lon":13.3,"radius":100},""" +
                  """{"lat":52.5,"lon":13.4,"radius":100}]"""
        val liste = Geofence.ausJson(roh)
        assertEquals(1, liste.size)
        assertEquals("Ruth", liste[0].name)
    }

    @Test
    fun fehlenderRadiusFaelltAufDenStandardZurueck() {
        val liste = Geofence.ausJson("""[{"name":"Ruth","lat":52.4,"lon":13.3}]""")
        assertEquals(Geofence.RADIUS_STANDARD_M, liste[0].radius)
    }

    @Test
    fun beiKleinerZoneGiltDerRadiusAlsGrenze() {
        // NEU in v0.46: Seit es Zonen mit 100 m Radius gibt (Kundenadressen
        // liegen dicht beieinander - Sylvia und Dr. Ehle nur 253 m), waere
        // die feste Grenze von 150 m sinnlos: Der Fix waere ungenauer als
        // die Zone gross, und die Entscheidung ein Muenzwurf.
        val klein = 100
        assertNull(Geofence.entscheide(900f, 120f, klein, "drin"))
        // Genau am Radius zaehlt es noch.
        assertEquals("verlassen",
            Geofence.entscheide(900f, klein.toFloat(), klein, "drin"))
    }

    @Test
    fun beiGrosserZoneBleibtDieFesteGrenze() {
        // Gegenprobe: Bei 200 m Radius darf die Grenze NICHT auf 200
        // steigen - ein Fix mit 180 m Ungenauigkeit sagt nichts aus.
        assertNull(Geofence.entscheide(900f, 180f, 200, "drin"))
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
        // FENSTER 900 statt 400: Seit v0.46 steht der Zonen-Abruf
        // zwischen dem try und dem Aufruf. Die Anforderung ist
        // unveraendert - der Aufruf muss im try-Block liegen -, nur
        // der Abstand ist groesser geworden.
        val davor = q.substring(maxOf(0, i - 900), i)
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

    // ------------------------------- Der erste Fix einer neuen Zone
    //
    // DER LIVEFALL VOM 30.08.2026: Nachdem 22 Kundenzonen dazugekommen
    // waren, meldete die App fuer JEDE ein Verlassen - mit Entfernungen
    // zwischen 6,5 und 18,7 km. Doreen war an keinem dieser Orte. In der
    // Messreihe standen danach 22 Abfahrten, die nie stattgefunden haben,
    // und genau daraus sollen spaeter die Fahrzeiten entstehen.
    //
    // Die Ursache lag NICHT in entscheide() - die Regel `erster Fix
    // meldet nichts` hat funktioniert. Der Aufrufer hat sich den zu
    // speichernden Zustand aus dem Ereignistext zusammengereimt, und der
    // ist beim ersten Fix leer: nicht "verlassen", also else-Zweig, also
    // "drin" - fuer eine Zone 17 km entfernt. Beim naechsten Takt war
    // die Abfahrt dann folgerichtig.

    @Test
    fun ersterFixWeitWegIstDraussen() {
        // Alex & Thomas, 17.615 m, Genauigkeit 100 m, Radius 100 m -
        // Zeichen fuer Zeichen die Werte aus logs/geofence.log.
        assertEquals("draussen", Geofence.lage(17615f, 100f, 100))
        // Gemeldet wird trotzdem nichts: Der erste Fix legt nur fest.
        assertEquals("", Geofence.entscheide(17615f, 100f, 100, null))
    }

    @Test
    fun ersterFixInDerZoneIstDrin() {
        // Die Gegenprobe - sonst waere der Fix nur die andere Haelfte
        // desselben Fehlers: Wer den ersten Fix pauschal "draussen" nennt,
        // meldet beim naechsten Takt ein Betreten, das nie stattfand.
        assertEquals("drin", Geofence.lage(20f, GUT, R))
        assertEquals("", Geofence.entscheide(20f, GUT, R, null))
    }

    @Test
    fun lageSagtNichtsWennSieNichtsSagenKann() {
        // Im Puffer-Ring und bei zu ungenauem Fix bleibt der bisherige
        // Zustand stehen - lage() darf dort NICHT raten.
        val imRing = (R + 30).toFloat()
        assertNull("Puffer-Ring", Geofence.lage(imRing, GUT, R))
        assertNull("zu ungenau", Geofence.lage(20f, 400f, R))
    }

    @Test
    fun lageUndEntscheideWidersprechenSichNie() {
        // Die eigentliche Absicherung: Wo entscheide() ein Betreten oder
        // Verlassen meldet, muss lage() denselben Zustand sagen. Liefen
        // die beiden auseinander, waere der gespeicherte Zustand wieder
        // falsch - nur an anderer Stelle.
        for (abstand in listOf(0f, 50f, 199f, 200f, 276f, 900f, 17615f)) {
            val l = Geofence.lage(abstand, GUT, R) ?: continue
            for (vorher in listOf(null, "drin", "draussen")) {
                val e = Geofence.entscheide(abstand, GUT, R, vorher) ?: continue
                val erwartet = when (e) {
                    "betreten" -> "drin"
                    "verlassen" -> "draussen"
                    else -> l          // erster Fix: nichts zu vergleichen
                }
                assertEquals("Abstand $abstand, vorher $vorher", erwartet, l)
            }
        }
    }

    @Test
    fun derZustandWirdNichtMehrAusDemEreignisAbgeleitet() {
        // WAECHTER GEGEN DEN RUECKFALL: pruefen() braucht Android und ist
        // hier nicht ausfuehrbar - geprueft wird deshalb der Quelltext.
        // Das zeigt nur, dass die alte Ableitung weg ist, nicht dass der
        // neue Weg wirkt; dafuer stehen die Faelle darueber.
        val q = File("src/main/java/com/jarvis/app/Geofence.kt").readText()
        assertTrue(
            "pruefen() leitet den Zustand wieder aus dem Ereignistext ab - " +
            "genau das hat am 30.08. die Messreihe verdorben",
            !q.contains("if (e.ereignis == \"verlassen\")"))
        assertTrue(
            "Der Zustand wird nicht mehr aus bewerte() uebernommen",
            q.contains("setzeZustand(ctx, zone.name, e.zustand)"))
    }
}
