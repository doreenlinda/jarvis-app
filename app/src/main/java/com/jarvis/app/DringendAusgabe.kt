package com.jarvis.app

import android.content.Context

/**
 * Was bei einer DRINGENDEN Meldung hoerbar wird (v0.33).
 *
 * ANLASS (Doreen, 05.08.2026): "Ich wuerde ungern wollen, dass in einem
 * Kundengespraech ploetzlich Jarvis' Mitteilung laut zu hoeren ist, da das
 * ja auch eine sehr persoenliche Notsituation sein kann, die ich nicht mit
 * anderen teilen moechte."
 *
 * Daraus die Trennung von SIGNAL und INHALT: Der Alarm sagt, DASS etwas
 * vorliegt - WAS es ist, erfaehrt sie erst, wenn sie selbst danach greift.
 * Das loest den Fall, ohne dass sie vorher an einen Schalter denken muss.
 *
 * Der Schalter existiert trotzdem, fuers Auto - aber standardmaessig AUS.
 * Vergisst sie ihn zurueckzustellen, bleibt der sichere Zustand aktiv; der
 * teure Fehler ist die laut vorgelesene Notlage, nicht ein Satz zu wenig.
 */
enum class Ausgabe {
    /** Neutraler Hinweis in Jarvis' Stimme, ohne jeden Inhalt. */
    HINWEIS,

    /** Die Kurzfassung selbst - nur, wenn sie es ausdruecklich will. */
    INHALT,
}

object DringendAusgabe {

    private const val PREFS = "jarvis"
    private const val SCHALTER = "dringend_inhalt_vorlesen"

    fun inhaltVorlesen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SCHALTER, false)

    fun setzeInhaltVorlesen(ctx: Context, an: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SCHALTER, an).apply()
    }

    /**
     * Die reine Entscheidung - bewusst ohne Android-Bezug, damit sie im
     * Cloud-Build VOR dem Bauen geprueft werden kann (der Laptop hat keine
     * Java-Werkzeugkette).
     *
     * `hatInhaltsTon` ist false, wenn der Server keine Tondatei mitgeschickt
     * hat. Dann bleibt nur der Hinweis - lieber ein neutraler Satz als
     * Stille, sonst waere nur die Vibration da.
     */
    fun waehle(inhaltVorlesen: Boolean, hatInhaltsTon: Boolean): Ausgabe =
        if (inhaltVorlesen && hatInhaltsTon) Ausgabe.INHALT else Ausgabe.HINWEIS
}

/**
 * Welches Symbol eine Meldung traegt (v0.34).
 *
 * ANLASS (Doreen, 05.08.2026): "Ein Warn-Symbol, das sich von normalen
 * Nachrichten unterscheidet, waere schon gut." Bis dahin trug AUCH die
 * dringende Meldung den Briefumschlag - ein E-Mail-Symbol aus der Zeit, als
 * ueber diesen Weg nur Postfach-Nachrichten liefen (Briefings, Manus). Fuer
 * eine Terminabsage sagt es das Falsche: "eine Nachricht" statt "dringend".
 *
 * Auf einen Blick unterscheidbar zu sein ist hier der ganze Zweck: Dieselbe
 * Anzeige kommt auch fuer harmlose Meldungen.
 */
object Symbole {

    fun fuer(art: String): Int =
        if (art == "dringend") android.R.drawable.stat_sys_warning
        else android.R.drawable.ic_dialog_email
}
