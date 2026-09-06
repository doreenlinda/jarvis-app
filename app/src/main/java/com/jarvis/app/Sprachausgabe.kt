package com.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Wie Jarvis' Stimme ausgegeben wird - an EINER Stelle.
 *
 * ANLASS (05.09.2026, Doreens Meldung): "Wichtige Meldungen von Jarvis
 * sollen in den Vordergrund treten, die Musik also automatisch leiser
 * werden. Aktuell hoere ich ihn auch nur ueber Handy." Ihr Handy haengt
 * dabei per Bluetooth am Auto.
 *
 * GEMESSEN, woran es lag: Die normalen Antworten liefen ueber einen
 * nackten MediaPlayer - ohne Audio-Attribute und ohne Audio-Fokus. Damit
 * gilt die Stimme fuer Android als MUSIK. Zwei Folgen:
 *
 *   1. Ohne Fokus-Antrag weiss das System nicht, dass gerade jemand
 *      spricht - die Musik laeuft unveraendert weiter.
 *   2. Als "Musik" mischt sich die Stimme mit der Musik, statt sich
 *      davor zu schieben.
 *
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK laesst die Musik leiser werden
 * statt sie anzuhalten - dieselbe Mechanik, die auch Navigationsansagen
 * nutzen. DAS Ducking ist ihr Ziel, und es haengt allein am Fokus.
 *
 * NACHGEBESSERT am 06.09.2026: v0.49 hatte zusaetzlich die Usage des
 * Players auf USAGE_ASSISTANT gestellt - und damit den Ton ueber
 * Bluetooth verloren (Messung siehe bei ATTRIBUTE). Routing und Absicht
 * sind seither getrennt.
 *
 * NICHT ANGETASTET: der Wecker-Ausgang der DRINGENDEN Meldungen
 * (USAGE_ALARM in WakeWordService). Der ist seit v0.32 bewusst so gebaut,
 * damit der Aufbruch-Alarm auch bei stummem Handy klingt - und ihr Handy
 * ist dauerhaft stumm. Wer ihn auf USAGE_ASSISTANT umstellt, macht ihn
 * zu Hause unhoerbar. Den Fokus fordert er trotzdem an: Die Musik soll
 * auch fuer ihn leiser werden.
 *
 * EHRLICHE GRENZE: Gedueckt werden kann nur, was das HANDY abspielt.
 * Kommt die Musik im Auto vom Radio, vom USB-Stick oder von einem
 * anderen Geraet, kann kein Handy sie leiser machen - dann bleibt nur
 * die Auto-Seite.
 */
object Sprachausgabe {

    /**
     * Fuer alles, was Jarvis SPRICHT (nicht fuer Alarmtoene).
     *
     * USAGE_MEDIA, NICHT USAGE_ASSISTANT - das ist an ihrem Geraet
     * GEMESSEN (06.09.2026) und keine Geschmacksfrage:
     *
     *   ohne Kopfhoerer   Ton kommt an
     *   mit Kopfhoerern   sie hoert GAR NICHTS
     *
     * v0.49 hatte hier USAGE_ASSISTANT gesetzt. Das beschreibt zwar
     * richtig, WAS die Stimme ist - ihr Geraet gibt diesen Strom ueber
     * Bluetooth aber nicht aus. Und Bluetooth ist genau der Weg, um den
     * es ihr ging: Kopfhoerer und Auto.
     *
     * Getrennt wird deshalb, was v0.49 vermischt hatte:
     *   ROUTING   (wohin der Ton geht)      -> hier, USAGE_MEDIA
     *   ABSICHT   (es spricht ein Assistent) -> FOKUS_ATTRIBUTE unten
     *
     * Das Ducking haengt am FOKUS-Antrag, nicht an der Usage des
     * Players - ihr eigentliches Ziel bleibt damit erhalten.
     */
    val ATTRIBUTE: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * NUR fuer den Fokus-Antrag - hierueber fliesst kein Ton, es ist die
     * Absichtserklaerung an das System. Deshalb darf sie ASSISTANT
     * bleiben, ohne das Routing zu beruehren.
     */
    private val FOKUS_ATTRIBUTE: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var anfrage: AudioFocusRequest? = null

    /**
     * Fordert den Fokus an - mehrfaches Aufrufen ist harmlos.
     *
     * WICHTIG: Der Fokus wird ueber die GANZE Antwort gehalten, nicht je
     * Sprechblock. Bei satzweisen Antworten (der Normalfall seit dem
     * 24.07.2026) wuerde die Musik sonst zwischen jedem Satz laut und
     * wieder leise werden - schlimmer als gar kein Ducking.
     */
    @Synchronized
    fun fokusAnfordern(context: Context) {
        if (anfrage != null) return
        try {
            val manager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val neu = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ).setAudioAttributes(FOKUS_ATTRIBUTE).build()
            manager.requestAudioFocus(neu)
            // Auch wenn das System ablehnt, wird gemerkt: Sonst bliebe ein
            // Antrag offen, den niemand mehr zurueckgibt.
            anfrage = neu
        } catch (_: Throwable) {
            // Ein misslungener Fokus-Antrag darf die Antwort NIE
            // aufhalten - sie zu hoeren ist wichtiger als das Ducking.
        }
    }

    /** Gibt den Fokus zurueck, damit die Musik wieder laut wird. */
    @Synchronized
    fun fokusFreigeben(context: Context) {
        val alt = anfrage ?: return
        anfrage = null
        try {
            val manager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.abandonAudioFocusRequest(alt)
        } catch (_: Throwable) {
        }
    }
}
