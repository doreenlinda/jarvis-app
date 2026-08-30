package com.jarvis.app

import android.content.Context
import android.location.Location
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45: Ortszonen - Etappe 1, die MESSUNG.
 *
 * Doreens Wunsch vom 29.08.2026: Jarvis soll auf Orte reagieren, ohne dass
 * sie die App oeffnet. Diese Etappe baut davon ausschliesslich die Messung:
 * Eine Zone wird betreten oder verlassen, der Server bekommt eine Meldung,
 * und im App-Postfach steht eine Notiz. Sonst nichts. Einkaufsliste,
 * Termine und Fahrzeiten kommen in Etappe 2 und 3.
 *
 * WARUM KEINE PLAY SERVICES: Androids Geofencing-API (`GeofencingClient`)
 * waere der naheliegende Weg, kommt aber aus `play-services-location` - und
 * die Abhaengigkeit gibt es in diesem Projekt nicht. Sie waere auch nicht
 * noetig: Der Weckwort-Dienst laeuft ohnehin rund um die Uhr im Vordergrund,
 * haelt einen Wake Lock und fragt jede Minute das Postfach ab. An diesen
 * Takt haengt sich die Zonenpruefung an - kein zweiter Dienst, kein zweiter
 * Wecker, keine neue Bibliothek. Dieselbe Ueberlegung wie am 28.07.2026,
 * als Firebase im Gespraech war und aus genau diesem Grund entfiel.
 *
 * WAS AUF DEM HANDY BLEIBT: die Zonen samt Koordinaten. An den Server geht
 * nur der Zonenname und die Richtung. Damit bleibt die Zusage aus v0.31
 * gewahrt - was den Laptop erreicht, ist ein Ortsname, kein Bewegungsprofil.
 *
 * WAS DIESE ETAPPE NICHT KANN, und das gehoert ehrlich dazu:
 *  - Nach einem Handy-Neustart, wenn der Dienst von selbst hochfaehrt und
 *    die App noch nie offen war, liefert die Ortung nichts (dokumentiert
 *    seit v0.31). Bei einem Zuruf ist das folgenlos, hier heisst es: kein
 *    Geofencing, bis sie die App einmal oeffnet. Schliessen wuerde das nur
 *    ACCESS_BACKGROUND_LOCATION ("Immer zulassen") - die Berechtigung, die
 *    in v0.31 bewusst nicht beantragt wurde. Ob es ohne sie im Alltag
 *    traegt, ist genau das, was diese Etappe messen soll.
 *  - Die Erkennung haengt am Minuten-Takt. Eine Zone wird also mit bis zu
 *    einer Minute Verzoegerung bemerkt, nicht in dem Moment, in dem sie die
 *    Schwelle ueberquert.
 */
object Geofence {

    private const val PREFS = "jarvis"
    private const val FELD_ZONEN = "geofence_zonen"
    private const val FELD_SERVER_ZONEN = "geofence_zonen_server"
    private const val FELD_SERVER_STAND = "geofence_zonen_geholt"
    private const val FELD_ZUSTAND = "geofence_zustand"

    /** Standardradius einer neuen Zone. */
    const val RADIUS_STANDARD_M = 200

    /**
     * Erst ausserhalb von Radius + PUFFER gilt die Zone als verlassen.
     *
     * WARUM UEBERHAUPT: Die Ortung ist in der Stadt auf 10-50 m genau, und
     * der gemeldete Punkt springt. Ohne diesen Abstand entstuende am
     * Zonenrand eine Kette aus "verlassen / betreten / verlassen" - im
     * Postfach waere das genau das Rauschen, das man nach drei Tagen
     * ignoriert. Der Server entprellt zusaetzlich, aber die erste und
     * wirksamste Bremse gehoert hierhin: Was gar nicht erst gemeldet wird,
     * muss dort auch nicht abgefangen werden.
     */
    const val PUFFER_M = 75

    /**
     * Ist der Fix ungenauer als das, wird gar nicht entschieden.
     *
     * Ein Netz-Fix in Gebaeuden kann 500 m danebenliegen. Daraus ein
     * "verlassen" abzuleiten waere schlimmer als zu schweigen - sie bekaeme
     * eine Meldung ueber etwas, das nicht stattgefunden hat.
     */
    const val GENAUIGKEIT_GRENZE_M = 150f

    /** Aelter als das, wird ein Fix nicht mehr fuer eine Entscheidung benutzt. */
    const val FIX_HALTBAR_MS = 5 * 60 * 1000L

    data class Zone(val name: String, val lat: Double, val lon: Double,
                    val radius: Int)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- Zonen

    /**
     * Alle Zonen - die vom Server UND die hier selbst gesetzten.
     *
     * DIE LOKALE GEWINNT bei gleichem Namen, und das ist Absicht: Eine Zone,
     * bei der Doreen WIRKLICH stand, sitzt genauer als jeder Geocoder. Ihr
     * getipptes "Zuhause" bleibt damit erhalten, obwohl der Server seit dem
     * 30.08.2026 ein gleichnamiges kennt, und fuer Karl (dessen Adresse in
     * ihrer Liste fehlt) kann sie beim naechsten Besuch weiterhin vor Ort
     * tippen.
     */
    fun zonen(ctx: Context): List<Zone> =
        zusammenfuehren(lokaleZonen(ctx), serverZonen(ctx))

    /**
     * Fuehrt die eigenen und die Server-Zonen zusammen - REINE Funktion.
     *
     * Bewusst ohne Context, damit der Cloud-Build sie prueft. Der Fehler
     * waere sonst teuer und stumm: Ein Name, der doppelt durchkommt, ergibt
     * zwei Zonen am selben Ort, und Doreen bekaeme jedes Ereignis doppelt
     * gemessen - in einer Messreihe, aus der spaeter Fahrzeiten werden
     * sollen.
     */
    fun zusammenfuehren(eigene: List<Zone>, vomServer: List<Zone>): List<Zone> {
        val namen = eigene.map { it.name.trim().lowercase() }.toSet()
        return eigene + vomServer.filter {
            it.name.trim().lowercase() !in namen
        }
    }

    /** Nur die hier auf dem Geraet gesetzten. */
    fun lokaleZonen(ctx: Context): List<Zone> = lies(ctx, FELD_ZONEN)

    /** Nur die vom Server geholten. */
    fun serverZonen(ctx: Context): List<Zone> = lies(ctx, FELD_SERVER_ZONEN)

    /** Wann zuletzt erfolgreich geholt wurde (0 = noch nie). */
    fun serverStand(ctx: Context): Long =
        prefs(ctx).getLong(FELD_SERVER_STAND, 0L)

    private fun lies(ctx: Context, feld: String): List<Zone> =
        ausJson(prefs(ctx).getString(feld, "") ?: "")

    /**
     * Liest eine gespeicherte Zonenliste - REINE Funktion, damit pruefbar.
     *
     * Eine kaputte Liste darf den Dienst nicht mitreissen: Dann gibt es
     * eben keine Zonen, und alles andere laeuft weiter. Ein Eintrag ohne
     * Namen wird uebersprungen - er waere spaeter nicht zuzuordnen.
     */
    fun ausJson(roh: String): List<Zone> {
        if (roh.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(roh)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) return@mapNotNull null
                Zone(name, o.optDouble("lat"), o.optDouble("lon"),
                     o.optInt("radius", RADIUS_STANDARD_M))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Legt eine Zone an oder ueberschreibt sie.
     *
     * DIE POSITION KOMMT VOM GERAET, NICHT AUS EINER ADRESSE: Sie steht in
     * ihrer Wohnung und tippt "Hier ist Zuhause". Damit ist kein Kartendienst
     * beteiligt, es muss keine Adresse in Koordinaten uebersetzt werden, und
     * die Zone sitzt genau dort, wo sie wirklich ist - nicht dort, wo ein
     * Geocoder die Hausnummer vermutet.
     */
    fun setzeZone(ctx: Context, name: String, loc: Location,
                  radius: Int = RADIUS_STANDARD_M) {
        val sauber = name.trim()
        if (sauber.isEmpty()) return
        // Nur die lokale Liste - aus demselben Grund wie bei entferneZone.
        val rest = lokaleZonen(ctx).filter { !it.name.equals(sauber, ignoreCase = true) }
        val arr = JSONArray()
        for (z in rest + Zone(sauber, loc.latitude, loc.longitude, radius)) {
            arr.put(JSONObject().apply {
                put("name", z.name)
                put("lat", z.lat)
                put("lon", z.lon)
                put("radius", z.radius)
            })
        }
        prefs(ctx).edit().putString(FELD_ZONEN, arr.toString()).apply()
        // Der Zustand der alten gleichnamigen Zone gilt nicht mehr - sonst
        // waere sie nach dem Neuanlegen sofort "drin", ohne dass je etwas
        // gemeldet wurde.
        setzeZustand(ctx, sauber, null)
    }

    /**
     * Holt die Zonenliste vom Server.
     *
     * WARUM DER SERVER SIE HAELT: Eine Zone entstand bis v0.45 nur, indem
     * Doreen dort steht und tippt. Fuer 24 Kunden- und Arztadressen ist das
     * nichts - also legt der Server sie aus den Adressen an, und das Handy
     * holt sie ab. Es geht NUR IN DIESE RICHTUNG; eine Position wird
     * weiterhin nie hochgeladen.
     *
     * BEI EINEM FEHLER BLEIBT DIE ALTE LISTE STEHEN, und das ist die
     * wichtigste Zeile hier: Ohne Netz, mit schlafendem Laptop oder bei
     * einem Serverfehler wuerde ein naiver "leer = keine Zonen mehr" alle
     * Zonen loeschen - und Doreen wuerde tagelang nichts messen, ohne dass
     * es jemandem auffaellt. Lieber eine veraltete Liste als gar keine.
     *
     * Gibt zurueck, wie viele Zonen jetzt vom Server bekannt sind, oder -1
     * bei einem Fehler.
     */
    fun holeVomServer(ctx: Context, client: OkHttpClient): Int {
        val p = prefs(ctx)
        val basis = (p.getString("url", "") ?: "").trim().trimEnd('/')
        val key = p.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return -1

        val anfrage = Request.Builder()
            .url("$basis/zonen?key=" + java.net.URLEncoder.encode(key, "UTF-8"))
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()
        return try {
            client.newCall(anfrage).execute().use { antwort ->
                if (!antwort.isSuccessful) return -1
                val koerper = antwort.body?.string() ?: return -1
                val arr = JSONObject(koerper).optJSONArray("zonen") ?: return -1
                // Eine leere Antwort wird NICHT uebernommen: Sie ist
                // ununterscheidbar von einem halb ausgefallenen Server, und
                // der Preis eines Irrtums waere der Verlust aller Zonen.
                if (arr.length() == 0) return 0
                p.edit()
                    .putString(FELD_SERVER_ZONEN, arr.toString())
                    .putLong(FELD_SERVER_STAND, System.currentTimeMillis())
                    .apply()
                arr.length()
            }
        } catch (_: Throwable) {
            -1
        }
    }

    fun entferneZone(ctx: Context, name: String) {
        // NUR die lokale Liste - sonst wuerden beim Loeschen einer einzigen
        // Zone alle Server-Zonen in die lokale Liste kopiert und waeren
        // danach nicht mehr aktualisierbar.
        val arr = JSONArray()
        for (z in lokaleZonen(ctx).filter { !it.name.equals(name.trim(), ignoreCase = true) }) {
            arr.put(JSONObject().apply {
                put("name", z.name); put("lat", z.lat)
                put("lon", z.lon); put("radius", z.radius)
            })
        }
        prefs(ctx).edit().putString(FELD_ZONEN, arr.toString()).apply()
        setzeZustand(ctx, name, null)
    }

    // ----------------------------------------------------------- Zustand

    /** "drin" / "draussen" / null (noch nie entschieden). */
    fun zustand(ctx: Context, name: String): String? {
        val roh = prefs(ctx).getString(FELD_ZUSTAND, "") ?: ""
        if (roh.isBlank()) return null
        return try {
            val o = JSONObject(roh)
            if (o.has(name.lowercase())) o.optString(name.lowercase(), "")
                .ifEmpty { null } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun setzeZustand(ctx: Context, name: String, wert: String?) {
        val roh = prefs(ctx).getString(FELD_ZUSTAND, "") ?: ""
        val o = try { if (roh.isBlank()) JSONObject() else JSONObject(roh) }
                catch (_: Throwable) { JSONObject() }
        if (wert == null) o.remove(name.lowercase()) else o.put(name.lowercase(), wert)
        prefs(ctx).edit().putString(FELD_ZUSTAND, o.toString()).apply()
    }

    // ----------------------------------------------------------- Pruefen

    /** Was bei einer Pruefung herauskam - fuer den Test und die Statuszeile. */
    data class Ergebnis(val zone: String, val ereignis: String,
                        val entfernung: Float, val genauigkeit: Float)

    /**
     * Entscheidet fuer EINE Zone, ob sich etwas geaendert hat.
     *
     * Reine Funktion ohne Nebenwirkungen - deshalb im Test pruefbar, ohne
     * ein Geraet zu haben. Genau das ist hier wichtig: Kotlin laesst sich
     * auf dem Laptop nicht ausfuehren, und was nicht als reine Funktion
     * vorliegt, wird erst auf ihrem Handy zum ersten Mal ausprobiert.
     */
    fun bewerte(zone: Zone, position: Location, vorher: String?): Ergebnis? {
        val genau = if (position.hasAccuracy()) position.accuracy else -1f
        val ziel = Location("zone").apply {
            latitude = zone.lat
            longitude = zone.lon
        }
        // distanceTo rechnet auf der Erdkugel, nicht auf einer flachen
        // Karte - eine selbstgebaute Formel waere hier die schlechtere Wahl.
        val abstand = position.distanceTo(ziel)
        val ereignis = entscheide(abstand, genau, zone.radius, vorher)
            ?: return null
        return Ergebnis(zone.name, ereignis, abstand, genau)
    }

    /**
     * Die eigentliche Entscheidung - bewusst OHNE Android-Typen.
     *
     * WARUM GETRENNT: Kotlin laesst sich auf dem Laptop nicht ausfuehren.
     * Was hier nicht als reine Funktion vorliegt, wird zum ersten Mal auf
     * ihrem Handy ausprobiert - und beim Weckwort-Umbau hat genau das drei
     * Fehlversuche gekostet. `Location.distanceTo` ist eine native
     * Android-Methode und im Unit-Test nicht verfuegbar; diese Funktion
     * dagegen laeuft im Cloud-Build VOR dem Bauen durch den Test.
     *
     * Rueckgabe: "betreten", "verlassen", "" (erster Fix - nur festlegen,
     * nichts melden) oder null (keine Aenderung / nicht entscheidbar).
     */
    fun entscheide(abstand: Float, genauigkeit: Float, radius: Int,
                   vorher: String?): String? {
        // Ein Fix, der ungenauer ist als die Zone gross, sagt nichts aus.
        // Daraus ein "verlassen" abzuleiten waere schlimmer als zu
        // schweigen: Sie bekaeme eine Meldung ueber etwas, das nicht
        // stattgefunden hat.
        //
        // DIE GRENZE HAENGT AM RADIUS (v0.46): Seit dem 30.08.2026 gibt es
        // Zonen mit 100 m statt 200 - Kundenadressen liegen dichter
        // beieinander, Sylvia und Dr. Ehle nur 253 m auseinander. Eine feste
        // Grenze von 150 m wuerde dort einen Fix akzeptieren, der ungenauer
        // ist als die Zone gross - und die Entscheidung waere ein Muenzwurf.
        // Der kleinere der beiden Werte gilt.
        val grenze = minOf(GENAUIGKEIT_GRENZE_M, radius.toFloat())
        if (genauigkeit > grenze) return null

        val drin = abstand <= radius
        val draussen = abstand > radius + PUFFER_M

        // Im Puffer-Ring wird NICHTS entschieden - dort bleibt es beim
        // bisherigen Zustand. Das ist der Kern der Hysterese.
        val neu = when {
            drin -> "drin"
            draussen -> "draussen"
            else -> return null
        }
        if (neu == vorher) return null

        // Der allererste Fix legt den Zustand nur FEST, ohne zu melden.
        // Sonst kaeme beim Einrichten sofort ein "Zuhause betreten", obwohl
        // sie sich nicht bewegt hat - beim Anlegen der Zone steht sie per
        // Definition mittendrin.
        if (vorher == null) return ""

        return if (neu == "drin") "betreten" else "verlassen"
    }

    /**
     * Der Einstieg aus dem Minuten-Takt des Weckwort-Dienstes.
     *
     * Blockiert (Positionsmessung) - gehoert deshalb NUR in einen
     * Hintergrund-Thread. Gibt zurueck, was gemeldet wurde.
     */
    fun pruefen(ctx: Context, client: OkHttpClient): List<Ergebnis> {
        val liste = zonen(ctx)
        if (liste.isEmpty()) return emptyList()
        if (!Standort.erlaubt(ctx) || !Standort.eingeschaltet(ctx)) return emptyList()

        val position = Standort.positionJetzt(ctx) ?: return emptyList()
        if (System.currentTimeMillis() - position.time > FIX_HALTBAR_MS) {
            return emptyList()
        }

        val gemeldet = mutableListOf<Ergebnis>()
        for (zone in liste) {
            val e = bewerte(zone, position, zustand(ctx, zone.name)) ?: continue
            val neuerZustand = if (e.ereignis == "verlassen") "draussen" else "drin"
            // ZUSTAND ZUERST: Faellt der Netzaufruf aus, ist die Meldung
            // verloren - aber sie wird nicht bei jedem Takt erneut versucht
            // und fuellt am Ende das Postfach. Eine verpasste Meldung ist
            // hier das kleinere Uebel.
            setzeZustand(ctx, zone.name, neuerZustand)
            if (e.ereignis.isEmpty()) continue   // erster Fix: nur festlegen
            if (melde(ctx, client, e)) gemeldet += e
        }
        return gemeldet
    }

    private fun melde(ctx: Context, client: OkHttpClient, e: Ergebnis): Boolean {
        val p = prefs(ctx)
        val basis = (p.getString("url", "") ?: "").trim().trimEnd('/')
        val key = p.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return false

        val koerper = FormBody.Builder()
            .add("key", key)
            .add("zone", e.zone)
            .add("ereignis", e.ereignis)
            .add("entfernung_m", e.entfernung.toInt().toString())
            .add("genauigkeit_m", e.genauigkeit.toInt().toString())
            .build()
        val anfrage = Request.Builder()
            .url("$basis/geofence")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(koerper)
            .build()
        return try {
            client.newCall(anfrage).execute().use { it.isSuccessful }
        } catch (_: Throwable) {
            // Kein Netz, Laptop schlaeft: Die Meldung ist weg. Das ist genau
            // einer der Punkte, die diese Etappe messen soll.
            false
        }
    }
}
