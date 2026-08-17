package com.jarvis.app

import android.content.Context

/**
 * Welchen Zustand zeigt der Voice Orb gerade?
 *
 * WARUM EIN EIGENES OBJEKT: Der Dienst meldet seinen Zustand seit v0.7 als
 * FREITEXT in die SharedPreferences ("Lauscht … Wert 0,31", "Weckwort
 * erkannt – ich höre Ihre Frage …"). Dieser Text ist fuer Doreens Auge
 * gebaut, nicht fuer eine Fallunterscheidung. Die Zuordnung Text -> Zustand
 * steht deshalb an EINER Stelle und ist eine reine Funktion - damit laesst
 * sie sich im Cloud-Build pruefen, BEVOR eine APK entsteht (Kotlin laesst
 * sich auf Doreens Rechner nicht ausfuehren, siehe KryptoFormatTest und
 * StandortFormatTest).
 *
 * SPRECHEN ist der eine Zustand, der NICHT aus dem Text ablesbar ist: Der
 * Dienst meldet "Frage gesendet – Antwort läuft …" und laesst diesen Text
 * stehen, waehrend die Antwort schon gesprochen wird. Deshalb setzt der
 * Abspielweg zusaetzlich einen Zeitstempel.
 */
object OrbZustand {

    const val AUS = "aus"
    const val RUHE = "ruhe"
    const val LAUSCHEN = "lauschen"
    const val DENKEN = "denken"
    const val SPRECHEN = "sprechen"
    const val FEHLER = "fehler"

    private const val SCHLUESSEL_SPRICHT = "orb_spricht_seit"

    /**
     * Sicherheitsnetz: Wird die App waehrend des Sprechens beendet, bleibt
     * der Zeitstempel stehen. Nach dieser Zeit gilt er als abgelaufen -
     * sonst zeigte der Orb bis zum naechsten Zuruf "spricht".
     */
    const val SPRECH_HOECHSTDAUER_MS = 180_000L

    /** Der Abspielweg meldet: ab jetzt ist Jarvis' Stimme zu hoeren. */
    fun spricht(ctx: Context) {
        try {
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                .putLong(SCHLUESSEL_SPRICHT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Antwort zu Ende gesprochen. */
    fun sprichtNicht(ctx: Context) {
        try {
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                .putLong(SCHLUESSEL_SPRICHT, 0L)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun sprichtSeit(ctx: Context): Long = try {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            .getLong(SCHLUESSEL_SPRICHT, 0L)
    } catch (_: Exception) {
        0L
    }

    /**
     * Die eigentliche Zuordnung - rein, ohne Android, damit testbar.
     *
     * REIHENFOLGE IST HIER DIE GANZE LOGIK:
     *  1. Laeuft der Dienst gar nicht, ist alles andere gegenstandslos.
     *  2. FEHLER schlaegt alles - eine Fehlermeldung darf nicht hinter
     *     einem munter pulsierenden Orb verschwinden.
     *  3. SPRECHEN vor DENKEN: Waehrend die Antwort laeuft, steht im Text
     *     weiterhin "Antwort läuft …". Ohne diesen Vorrang bliebe der Orb
     *     die ganze Antwort ueber im Denk-Zustand.
     *  4. LAUSCHEN meint, dass Jarvis IHRE Frage aufnimmt - nicht das
     *     stille Warten auf das Weckwort. Das ist RUHE.
     */
    @JvmStatic
    fun ausStatus(
        status: String,
        dienstLebt: Boolean,
        sprichtSeit: Long,
        jetzt: Long,
    ): String {
        if (!dienstLebt) return AUS
        val t = status.lowercase()
        if (t.startsWith("fehler")) return FEHLER
        if (t.contains("weckwort erkannt") || t.contains("ich höre noch") ||
            t.contains("ich hoere noch")
        ) {
            return LAUSCHEN
        }
        if (t.contains("sende an jarvis") || t.contains("antwort läuft") ||
            t.contains("antwort laeuft") || t.contains("versuche klassisch")
        ) {
            return DENKEN
        }
        return RUHE
    }
}
