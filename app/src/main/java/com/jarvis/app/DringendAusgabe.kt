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
        if (hatInhaltsTon) Ausgabe.INHALT else Ausgabe.HINWEIS
}
