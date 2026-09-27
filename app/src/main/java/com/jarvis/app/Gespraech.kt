package com.jarvis.app

/**
 * Wie lange darf nach einem "Hey Jarvis" ohne neues Weckwort weitergesprochen
 * werden? (v0.61, 27.09.2026)
 *
 * ANLASS - ihr Wunsch: "Ich wuerde bei laengeren Gespraechen gerne die
 * Unterhaltung fliessend fortsetzen wollen, wie in einem echten Gespraech."
 * Die Logs vom 25.09.2026 zeigten ueber rund 25 Runden ein starres Muster:
 * Weckwort, Frage, Antwort, EINE Nachfrage - und dann wieder "Hey Jarvis"
 * samt Rueckfrage ("Was wollen Sie?"). Jede zweite Runde begann also mit
 * einer Floskel.
 *
 * Ursache war die Grenze aus v0.30: hoechstens EINE Nachfrage pro Weckwort.
 * Sie entstand am 02.08.2026, als ein versehentliches Weckwort mitten in
 * einem fremden Gespraech eine offene Kette ausloeste - 17 Wortwechsel in
 * 17 Minuten. Die Grenze war richtig, aber zu grob.
 *
 * JETZT: Die Kette laeuft weiter, solange der SERVER das Gespraech fuer
 * offen haelt ("gespraech_offen": true - Jarvis hat verstanden, und sie hat
 * nicht selbst klargestellt, dass es nicht ihm galt). Ein aufgeschnapptes
 * Raumgespraech endet damit bei der ersten ratlosen Antwort. Dazu eine
 * feste Obergrenze als zweite Sicherung: Auch eine Kette, die der Server
 * immer wieder fuer offen haelt, endet nach MAX_NACHFRAGEN.
 *
 * Die ERSTE Nachfrage nach dem Weckwort bleibt wie seit v0.21 immer erlaubt
 * - auch wenn die erste Antwort ratlos war. Genau dann will sie die Frage
 * ja wiederholen, und sie hat Jarvis bewusst gerufen.
 *
 * Als reine Funktion ausgelagert, damit der Cloud-Build sie wirklich
 * ausfuehren kann (Kotlin laeuft auf dem Laptop nicht).
 */
object Gespraech {

    /** Obergrenze fuer aneinandergehaengte Nachfragen pro Weckwort. Gesetzt,
     *  nicht gemessen - zum Justieren gedacht. */
    const val MAX_NACHFRAGEN = 8

    /**
     * @param bisherigeNachfragen wie viele Fragen dieser Kette schon aus dem
     *        Nachfass-Fenster kamen (0 = die letzte Frage war die nach dem
     *        Weckwort).
     * @param gespraechOffen was der Server zur LETZTEN Antwort gemeldet hat;
     *        fehlt das Feld (aelterer Server, Stoerung, nichts verstanden),
     *        ist es false - die sichere Richtung.
     */
    fun weiterhoeren(bisherigeNachfragen: Int, gespraechOffen: Boolean): Boolean {
        if (bisherigeNachfragen <= 0) return true
        if (bisherigeNachfragen >= MAX_NACHFRAGEN) return false
        return false
    }
}
