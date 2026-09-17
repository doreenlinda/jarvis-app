package com.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

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
    // `by lazy`, damit diese Klasse OHNE Android-Laufzeit ladbar ist:
    // AudioAttributes.Builder() ist im Unit-Test nur eine Attrappe, die
    // wirft. Als Feld auf Objektebene scheiterte damit die gesamte
    // Klasseninitialisierung (ExceptionInInitializerError) - und die
    // reinen Auswahl-Funktionen darunter waeren nicht pruefbar gewesen.
    // Im Betrieb identisch: Die Attribute entstehen beim ersten Zugriff.
    val ATTRIBUTE: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * NUR fuer den Fokus-Antrag - hierueber fliesst kein Ton, es ist die
     * Absichtserklaerung an das System. Deshalb darf sie ASSISTANT
     * bleiben, ohne das Routing zu beruehren.
     */
    private val FOKUS_ATTRIBUTE: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    // ----------------------------------------------------------------
    // Rettung, wenn der Ton im Nirgendwo landet (17.09.2026)
    // ----------------------------------------------------------------
    //
    // ANLASS, Doreens Meldung: "Ich rufe ihn im Auto auf, hoere ihn aber
    // nicht, obwohl die Musik unterbricht."
    //
    // GEMESSEN ueber die Ton-Diagnose, mit ihren Ortszonen abgeglichen:
    //
    //   zuhause (08:04, 13:48)   Ausgang  8 = BLUETOOTH_A2DP   -> hoerbar
    //   im Auto (16:58 - 17:20)  Ausgang 25 = REMOTE_SUBMIX    -> nichts
    //
    // Typ 25 ist laut Android-Quelltext "a device type for rerouting audio
    // within the Android framework between mixes and system applications" -
    // ein framework-interner Umleitungskanal, KEIN Lautsprecher. Die App
    // hat dabei alles richtig gemacht: gespielt=2/2, fehler=0. Der Ton ging
    // raus und verschwand. Dass die Musik trotzdem duckt, passt dazu - das
    // haengt am FOKUS, und der erreicht das System weiterhin.
    //
    // Bei Android Auto uebernimmt Google das Audio-Routing und stellt nur
    // zugelassene App-Kategorien auf die Autolautsprecher durch; fuer
    // Assistenten gibt es gar keine Kategorie. Nicht zugelassene Apps
    // streamen dort bekanntermassen "into nowhere".
    //
    // WAS DIESE UMLEITUNG TUT: Landet der Player im Submix, wird er auf
    // einen ECHTEN Ausgang gelenkt - bevorzugt Bluetooth, also den Weg,
    // auf dem im Auto auch die Musik laeuft.
    //
    // ENG GEFASST, und das ist Absicht: Sie greift NUR bei Typ 25. Ist der
    // Ausgang normal (zuhause A2DP), passiert nichts. v0.49 hat an genau
    // dieser Stelle schon einmal den Ton ueber Bluetooth komplett gekostet,
    // weil eine Aenderung ohne Not alle Faelle traf.
    //
    // OB ES TRAEGT, IST OFFEN: Solange Android Auto laeuft, kann es das
    // Routing an sich ziehen. Der Versuch ist billig, und die Ton-Diagnose
    // misst danach, was wirklich passiert ist.

    /** Landet dieser Player im framework-internen Nirgendwo? */
    fun brauchtUmleitung(routedTyp: Int): Boolean =
        routedTyp == AudioDeviceInfo.TYPE_REMOTE_SUBMIX

    /**
     * Welcher ECHTE Ausgang stattdessen - REINE Funktion, damit der
     * Cloud-Build sie ausfuehren kann (Kotlin laesst sich auf dem Laptop
     * nicht testen; beim Weckwort-Umbau hat genau das drei Fehlversuche
     * gekostet).
     *
     * Bluetooth zuerst, weil das der Weg ins Auto und zu den Kopfhoerern
     * ist. Der eingebaute Lautsprecher steht am Ende: besser aus dem Handy
     * gehoert als gar nicht. Virtuelle Ausgaenge scheiden aus - auf einen
     * zweiten Submix umzuleiten waere sinnlos.
     */
    fun echterAusgang(typen: IntArray): Int {
        val rang = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        for (t in rang) if (typen.contains(t)) return t
        return -1
    }

    /**
     * Zwischen prepare() und start() aufrufen. Gibt den Typ zurueck, auf
     * den umgeleitet wurde, sonst -1 (auch wenn gar nichts noetig war).
     *
     * Ein Fehlschlag ist folgenlos: Dann bleibt es beim bisherigen Weg -
     * schlechter als vorher wird es nie.
     */
    fun umleitenWennNoetig(mp: MediaPlayer, context: Context): Int = try {
        val ist = mp.routedDevice?.type ?: -1
        if (!brauchtUmleitung(ist)) {
            -1
        } else {
            val manager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val geraete = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val ziel = echterAusgang(geraete.map { it.type }.toIntArray())
            val geraet = geraete.firstOrNull { it.type == ziel }
            if (geraet != null && mp.setPreferredDevice(geraet)) ziel else -1
        }
    } catch (_: Throwable) {
        -1
    }

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
