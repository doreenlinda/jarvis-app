package com.jarvis.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.RemoteInput

/**
 * Auf eine eingegangene WhatsApp-Nachricht ANTWORTEN (v0.38).
 *
 * WIE DAS GEHT, UND WARUM SO
 * ---------------------------
 * Jede WhatsApp-Benachrichtigung traegt eine Direct-Reply-Aktion - denselben
 * Mechanismus, mit dem eine Smartwatch antwortet: eine Aktion mit RemoteInput
 * und einem PendingIntent, den WhatsApp selbst gebaut hat. Wir tippen also
 * nichts fern und automatisieren keine Oberflaeche; wir benutzen genau den
 * Weg, den WhatsApp fuer Zweitgeraete vorgesehen hat.
 *
 * DIE MESSUNG (09.-11.08.2026) HAT DEN ZUSCHNITT BESTIMMT:
 *   * 25 von 25 Eingaengen trugen eine Antwort-Aktion - sie ist immer da.
 *   * Sie lebt aber nur solange die Benachrichtigung lebt (median 72 s),
 *     und das Ende war fast immer "ersetzt/aktualisiert".
 *
 * "Ersetzt" heisst: WhatsApp schickt fuer denselben Chat eine NEUE
 * Benachrichtigung mit frischer Aktion. Deshalb wird hier je Gespraech die
 * JEWEILS NEUESTE Aktion behalten - beim Eintreffen, nicht erst beim
 * Antworten. Wer erst im Moment des Sendens sucht, findet in der Haelfte der
 * Faelle nichts mehr.
 *
 * ENDGUELTIG weg ist der Kanal erst, wenn sie den Chat liest (auch vom
 * Desktop aus). Ob er noch traegt, weiss man nicht im Voraus - man erfaehrt
 * es beim Versuch. Genau deshalb meldet [senden] ein ehrliches Ergebnis
 * zurueck, statt Erfolg anzunehmen: Der Server wartet darauf und sagt ihr
 * erst danach, was Sache ist.
 *
 * NUR IM ARBEITSSPEICHER. Ein PendingIntent ist ein lebendes Token, kein
 * Datum - er laesst sich nicht auf die Platte schreiben. Stirbt der Prozess,
 * sind die Kanaele weg; der Lauschdienst laeuft aber ohnehin dauerhaft im
 * Vordergrund. Nach einem Neustart des Handys ist der erste eingehende
 * Nachrichtenschub der, der die Kanaele wieder auffuellt.
 */
object WhatsAppAntwort {

    /** Ergebniswoerter - dieselben Zeichenketten erwartet der Server. */
    const val GESENDET = "gesendet"
    const val KEIN_KANAL = "kein_kanal"
    const val FEHLER = "fehler"

    private class Kanal(
        val absender: String,
        val intent: PendingIntent,
        val eingaben: Array<RemoteInput>,
        val ziel: RemoteInput,
        val gemerktUm: Long,
    )

    /** Nur so viele Gespraeche behalten, wie im Alltag vorkommen. Aeltestes
     *  fliegt zuerst raus - der Speicher darf nicht unbegrenzt wachsen. */
    private const val MAX_KANAELE = 30

    private val kanaele = LinkedHashMap<String, Kanal>()

    /** Schluessel: der Absendername, wie ihn die Benachrichtigung traegt.
     *  Genau denselben Namen kennt der Server aus der Datenbank, weil er von
     *  dort stammt - deshalb reicht ein einfacher Vergleich ohne Gross-/
     *  Kleinschreibung, und es muss hier nichts geraten werden. */
    private fun schluessel(absender: String): String = absender.trim().lowercase()

    /**
     * Die Antwort-Aktion dieser Benachrichtigung festhalten.
     *
     * Bewusst OHNE Inhalt: gespeichert wird der Absendername und das
     * Sende-Token, nicht der Nachrichtentext.
     */
    @Synchronized
    fun merken(absender: String, notification: Notification?) {
        val name = absender.trim()
        if (name.isEmpty() || notification == null) return
        val gefunden = antwortAktion(notification) ?: return
        val (intent, eingaben, ziel) = gefunden
        val k = schluessel(name)
        // Neueste gewinnt: erst entfernen, dann anhaengen, damit die
        // Reihenfolge stimmt und das Aelteste wirklich zuerst rausfaellt.
        kanaele.remove(k)
        while (kanaele.size >= MAX_KANAELE) {
            val aeltester = kanaele.keys.firstOrNull() ?: break
            kanaele.remove(aeltester)
        }
        kanaele[k] = Kanal(name, intent, eingaben, ziel, System.currentTimeMillis())
    }

    /** Gibt es fuer diesen Absender ueberhaupt einen Kanal? (nur fuer die
     *  Anzeige in der App - das Senden probiert es ohnehin selbst) */
    @Synchronized
    fun bekannteGespraeche(): Int = kanaele.size

    /**
     * Antworten. Rueckgabe ist eines der drei Ergebniswoerter oben.
     *
     * WICHTIG: Ein "gesendet" heisst, dass WhatsApp den Auftrag angenommen
     * hat - nicht, dass die Nachricht beim Empfaenger angekommen ist. Weiter
     * reicht unsere Kenntnis nicht, und genau so wird es ihr auch gesagt.
     */
    @Synchronized
    fun senden(ctx: Context, absender: String, text: String): Pair<String, String> {
        val inhalt = text.trim()
        if (inhalt.isEmpty()) return FEHLER to "leerer Text"
        val kanal = kanaele[schluessel(absender)]
            ?: return KEIN_KANAL to "kein offener Kanal fuer $absender"
        return try {
            val intent = Intent()
            val werte = Bundle()
            werte.putCharSequence(kanal.ziel.resultKey, inhalt)
            // ALLE RemoteInputs der Aktion mitgeben, nicht nur das Ziel -
            // sonst verwirft Android die Werte, wenn die Aktion mehrere
            // Eingaben deklariert.
            RemoteInput.addResultsToIntent(kanal.eingaben, intent, werte)
            // Ab Android 9 will das System wissen, woher der Text kommt.
            // Ohne diese Angabe lehnen manche Apps die Eingabe ab. Aeltere
            // Systeme kennen den Aufruf nicht - dort entfaellt er einfach
            // (minSdk ist 26).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            kanal.intent.send(ctx, 0, intent)
            // Der Kanal ist mit dem Senden verbraucht: WhatsApp zieht die
            // Benachrichtigung danach zurueck. Ihn stehen zu lassen hiesse,
            // beim naechsten Mal ein totes Token zu benutzen und faelschlich
            // "gesendet" zu melden.
            kanaele.remove(schluessel(absender))
            GESENDET to ""
        } catch (e: PendingIntent.CanceledException) {
            // Der haeufige Fall: Sie hat den Chat gelesen, WhatsApp hat die
            // Antwortmoeglichkeit zurueckgezogen.
            kanaele.remove(schluessel(absender))
            KEIN_KANAL to "Antwortmoeglichkeit verfallen"
        } catch (e: Throwable) {
            FEHLER to (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120)
        }
    }

    /**
     * Die Aktion heraussuchen, mit der sich antworten laesst.
     *
     * Bevorzugt wird eine Aktion, die Android ausdruecklich als ANTWORT
     * kennzeichnet (SEMANTIC_ACTION_REPLY). Erst wenn es keine gibt, zaehlt
     * die erste Aktion mit einer Freitext-Eingabe. Ohne diese Reihenfolge
     * koennte eine andere Aktion mit Texteingabe zuerst kommen - und dann
     * ginge der Text irgendwohin, nur nicht in den Chat.
     */
    private fun antwortAktion(
        n: Notification
    ): Triple<PendingIntent, Array<RemoteInput>, RemoteInput>? {
        val aktionen = n.actions ?: return null
        val brauchbar = aktionen.filter { a ->
            a?.actionIntent != null && a.remoteInputs?.any { it?.allowFreeFormInput == true } == true
        }
        if (brauchbar.isEmpty()) return null
        val bevorzugt = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                brauchbar.firstOrNull {
                    it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
                }
            } else null
        ) ?: brauchbar.first()
        val eingaben = bevorzugt.remoteInputs ?: return null
        val ziel = eingaben.firstOrNull { it?.allowFreeFormInput == true } ?: return null
        return Triple(bevorzugt.actionIntent, eingaben, ziel)
    }
}
