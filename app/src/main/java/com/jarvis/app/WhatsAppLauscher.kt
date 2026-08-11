package com.jarvis.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Liest eingehende WhatsApp-Nachrichten als Android-BENACHRICHTIGUNG mit und
 * schickt sie verschluesselt an Jarvis (v0.32).
 *
 * WARUM SO UND NICHT UEBER METAS SCHNITTSTELLE (Entscheidung 05.08.2026):
 * Eine Nummer, die fuer die WhatsApp Cloud API registriert wird, kann nicht
 * mehr mit der normalen WhatsApp-App genutzt werden, und zurueck geht es
 * nicht - Doreens Kundenkanal haette daran gehangen. Die "Coexistence"-
 * Variante verlangt die WhatsApp-BUSINESS-App, ein Meta-Business-Konto mit
 * Verifizierung und einen Anbieter als Zwischenschicht. Der Weg ueber die
 * Benachrichtigung laesst ihre Nummer unberuehrt und haelt den Inhalt lokal.
 *
 * WAS DAS KOSTET, offen benannt: Die Berechtigung ist unter Android ALLES
 * ODER NICHTS - technisch koennte dieser Dienst die Benachrichtigungen jeder
 * App sehen. Die Beschraenkung auf WhatsApp steckt hier im Code (siehe
 * [WhatsAppFilter]), nicht in der Berechtigung. Deshalb ist sie zusaetzlich
 * ueber einen eigenen Schalter in der App abschaltbar.
 *
 * GRENZEN, die zur Sache gehoeren: Es kommt nur an, was auch wirklich als
 * Benachrichtigung erscheint - kein Verlauf, keine alten Chats. Was sie am
 * Desktop schneller liest, kann verpuffen.
 */
class WhatsAppLauscher : NotificationListenerService() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Wann kam welche Benachrichtigung an? Nur fuer die Lebensdauer-Messung;
     *  Schluessel ist Androids Notification-Key, kein Inhalt. */
    private val startZeiten = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!istEingeschaltet(applicationContext)) return

        val extras = n.notification?.extras ?: return
        val titel = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        // Ob es ein Gruppenchat ist, WEISS das System - hier wird nichts aus
        // dem Text geraten. Der erste Serverentwurf tat genau das und haette
        // "Kurze Frage: passt Donnerstag auch?" als Gruppennachricht
        // verschluckt.
        val istGruppe = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null

        val flags = n.notification?.flags ?: 0
        if (!WhatsAppFilter.weiterleiten(
                paket = n.packageName ?: "",
                titel = titel,
                text = text,
                istSammelmeldung = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                istDauerhaft = (flags and Notification.FLAG_ONGOING_EVENT) != 0,
            )
        ) return

        // ANTWORT-AKTION FESTHALTEN (v0.38). Die Messung aus v0.37 hat
        // beides beantwortet: Die Aktion ist immer da (25 von 25), lebt aber
        // nur solange die Benachrichtigung (median 72 s, Ende fast immer
        // "ersetzt"). Wer sie erst im Moment des Antwortens sucht, findet in
        // der Haelfte der Faelle nichts mehr - deshalb wird sie JETZT
        // festgehalten, beim Eintreffen. Gespeichert wird der Absendername
        // und das Sende-Token, kein Inhalt.
        WhatsAppAntwort.merken(titel, n.notification)

        // Die Messung aus v0.37 laeuft unveraendert weiter: Sie kostet nichts
        // und zeigt, ob sich die Lage aendert (etwa nach einem
        // WhatsApp-Update).
        val antwortbar = hatAntwortAktion(n.notification)
        merkeStart(n.key)

        // MESSUNG (v0.38): Kaeme die App an die .opus-Datei einer
        // Sprachnachricht heran? Laeuft hoechstens alle sechs Stunden, liest
        // keine Datei und uebertraegt keinen Ton.
        SprachnachrichtProbe.vielleichtMessen(applicationContext, text)

        senden(titel, text, istGruppe, antwortbar)
    }

    /**
     * Ist eine Direct-Reply-Aktion vorhanden? Das ist derselbe Mechanismus,
     * mit dem eine Smartwatch antwortet: eine Aktion mit RemoteInput.
     *
     * Reine MESSZAHL - das Festhalten der Aktion macht seit v0.38
     * [WhatsAppAntwort.merken]. Beides bleibt nebeneinander stehen: Die
     * Messung kostet nichts und zeigt, ob sich die Lage aendert, etwa nach
     * einem WhatsApp-Update.
     */
    private fun hatAntwortAktion(notification: Notification?): Boolean = try {
        notification?.actions?.any { aktion ->
            aktion?.remoteInputs?.any { it != null } == true
        } == true
    } catch (_: Throwable) {
        false
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val n = sbn ?: return
        // Denselben Schalter beachten wie beim Mitlesen: Ist WhatsApp in der
        // App abgeschaltet, wird auch nicht gemessen.
        if (!istEingeschaltet(applicationContext)) return
        if ((n.packageName ?: "") !in WhatsAppFilter.pakete()) return
        val start = startZeiten.remove(n.key) ?: return
        val sekunden = (System.currentTimeMillis() - start) / 1000
        meldeEnde(sekunden, reason)
    }

    private fun merkeStart(key: String?) {
        val k = key ?: return
        // Obergrenze, damit die Tabelle nicht unbegrenzt waechst, falls
        // Entfernen-Ereignisse ausbleiben (etwa nach einem Neustart des
        // Dienstes). Aelteste Eintraege fallen zuerst raus.
        if (startZeiten.size > 200) {
            startZeiten.keys.take(100).forEach { startZeiten.remove(it) }
        }
        startZeiten[k] = System.currentTimeMillis()
    }

    private fun meldeEnde(sekunden: Long, grund: Int) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return
        thread {
            try {
                // Hier gehen NUR eine Dauer und ein Grundcode raus, kein
                // Inhalt und kein Absender - deshalb auch nichts zu
                // verschluesseln.
                client.newCall(
                    Request.Builder()
                        .url("$basis/whatsapp-diagnose")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(
                            FormBody.Builder()
                                .add("key", key)
                                .add("lebensdauer_s", sekunden.toString())
                                .add("grund", grund.toString())
                                .build()
                        )
                        .build()
                ).execute().close()
            } catch (_: Throwable) {
                // Eine verlorene Messzeile ist folgenlos.
            }
        }
    }

    private fun senden(titel: String, text: String, istGruppe: Boolean, antwortbar: Boolean) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return

        thread {
            try {
                val e2e = Krypto.aktiv(ctx)
                val bau = FormBody.Builder()
                    .add("key", key)
                    .add("absender", if (e2e) Krypto.verschluesselnText(ctx,titel) else titel)
                    .add("text", if (e2e) Krypto.verschluesselnText(ctx,text) else text)
                    .add("gruppe", if (istGruppe) "1" else "0")
                    .add("antwortbar", if (antwortbar) "1" else "0")
                if (e2e) bau.add("e2e", "1")
                // Die ANTWORT wird jetzt ausgewertet (v0.38): Sie kann einen
                // Sendebefehl fuer eine automatische Antwort enthalten
                // (Terminbestaetigung / Absage). Dieser Weg ist bewusst
                // gewaehlt - die Verbindung steht hier ohnehin, damit ist
                // der Befehl binnen einer Sekunde da. Ueber den Postausgang
                // (1x pro Minute) waere er bei rund 72 s Lebensdauer der
                // Antwort-Aktion regelmaessig zu spaet.
                val roh = client.newCall(
                    Request.Builder()
                        .url("$basis/whatsapp-nachricht")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(bau.build())
                        .build()
                ).execute().use { it.body?.string() ?: "" }
                if (roh.isNotBlank()) automatischAntworten(ctx, basis, key, roh)
            } catch (_: Throwable) {
                // Eine verlorene Nachricht ist aergerlich, aber sie darf weder
                // die App noch das Lauschen stoeren. Der Server ist ohnehin
                // gegen Doppelzustellungen abgesichert.
            }
        }
    }

    /**
     * Traegt die Serverantwort einen Sendebefehl? Dann jetzt antworten.
     *
     * Der Server entscheidet, OB und WAS geantwortet wird - hier wird nur
     * ausgefuehrt. Er beschraenkt das auf zwei Faelle, die Doreen freigegeben
     * hat (Terminbestaetigung, Absage), und formuliert den Text selbst aus
     * Kalenderdaten.
     *
     * Das ERGEBNIS wird zurueckgemeldet, auch wenn niemand darauf wartet:
     * Nur so kann der Server festhalten, was eine Kundin wirklich bekommen
     * hat. Ohne diese Meldung stuende im Briefing eine Zustellung, die
     * womoeglich nie stattfand.
     */
    private fun automatischAntworten(
        ctx: Context,
        basis: String,
        key: String,
        rohantwort: String,
    ) {
        val daten = try {
            Krypto.auspacken(ctx, org.json.JSONObject(rohantwort))
        } catch (_: Throwable) {
            return
        }
        val auftrag = daten.optJSONObject("antwort") ?: return
        val an = auftrag.optString("empfaenger", "")
        val was = auftrag.optString("text", "")
        val nachrichtId = auftrag.optInt("nachricht_id", 0)
        if (an.isEmpty() || was.isEmpty() || nachrichtId <= 0) return

        val (status, detail) = try {
            WhatsAppAntwort.senden(ctx, an, was)
        } catch (e: Throwable) {
            WhatsAppAntwort.FEHLER to e.javaClass.simpleName.take(60)
        }
        try {
            client.newCall(
                Request.Builder()
                    .url("$basis/whatsapp-gesendet")
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .post(
                        FormBody.Builder()
                            .add("key", key)
                            .add("sende_id", "")
                            .add("status", status)
                            .add("detail", detail.take(120))
                            .add("nachricht_id", nachrichtId.toString())
                            .build()
                    )
                    .build()
            ).execute().close()
        } catch (_: Throwable) {
            // Geht die Meldung verloren, bleibt die Spalte leer - der
            // Server behauptet dann KEINE Zustellung. Richtige Richtung.
        }
    }

    companion object {
        private const val PREFS = "jarvis"
        private const val SCHALTER = "whatsapp_an"

        fun istEingeschaltet(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SCHALTER, false)

        fun setzeSchalter(ctx: Context, an: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(SCHALTER, an).apply()
        }

        /** Hat der Nutzer den Benachrichtigungs-Zugriff im System erteilt? */
        fun zugriffErteilt(ctx: Context): Boolean = try {
            val erlaubte = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: ""
            erlaubte.contains(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Die reine Entscheidung "weiterleiten oder nicht" - bewusst ohne jeden
 * Android-Bezug, damit sie im Cloud-Build VOR dem Bauen geprueft werden kann
 * (der Laptop hat keine Java-Werkzeugkette; genau dieses Muster hat schon bei
 * Krypto und Standort Fehler vor dem Ausrollen gefunden).
 */
object WhatsAppFilter {

    /** WhatsApp und WhatsApp Business - sonst nichts. */
    private val PAKETE = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** Damit auch die Lebensdauer-Messung genau dieselbe Liste benutzt und
     *  nicht irgendwann eine zweite, abweichende Kopie entsteht. */
    fun pakete(): Set<String> = PAKETE

    /**
     * Benachrichtigungen, die gar keine Nachricht sind. WhatsApp zeigt
     * staendig solche an - ohne diesen Filter liefe fuer jede davon ein
     * Modellaufruf auf dem Server.
     */
    private val KEINE_NACHRICHT = Regex(
        """^(WhatsApp|\d+ neue Nachrichten.*|Neue Nachrichten|""" +
            """Nach neuen Nachrichten suchen\.\.\.|Nachrichten werden geprüft.*|""" +
            """Verpasster Anruf.*|Eingehender Anruf.*|.* ruft an\.?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun weiterleiten(
        paket: String,
        titel: String,
        text: String,
        istSammelmeldung: Boolean,
        istDauerhaft: Boolean,
    ): Boolean {
        if (paket !in PAKETE) return false
        // Sammelmeldung ("3 neue Nachrichten von 2 Chats") und der dauerhafte
        // "WhatsApp laeuft"-Hinweis tragen keinen Inhalt.
        if (istSammelmeldung || istDauerhaft) return false
        val t = titel.trim()
        val x = text.trim()
        if (t.isEmpty() || x.isEmpty()) return false
        if (KEINE_NACHRICHT.matches(t) || KEINE_NACHRICHT.matches(x)) return false
        return true
    }
}
