package com.jarvis.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
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

        senden(titel, text, istGruppe)
    }

    private fun senden(titel: String, text: String, istGruppe: Boolean) {
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
                if (e2e) bau.add("e2e", "1")
                client.newCall(
                    Request.Builder()
                        .url("$basis/whatsapp-nachricht")
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .post(bau.build())
                        .build()
                ).execute().close()
            } catch (_: Throwable) {
                // Eine verlorene Nachricht ist aergerlich, aber sie darf weder
                // die App noch das Lauschen stoeren. Der Server ist ohnehin
                // gegen Doppelzustellungen abgesichert.
            }
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
