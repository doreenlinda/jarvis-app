package com.jarvis.app

import android.content.Context
import android.media.MediaPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Die kurze Rueckfrage nach dem Weckwort - LOKAL, ohne Netz.
 *
 * ANLASS (07.09.2026, Doreens Beobachtung): "Das 'Was wollen Sie denn
 * schon wieder' muesste ja vor meiner Frage kommen. So waere doch die
 * klaffende Luecke weg."
 *
 * Ihre ERKLAERUNG dafuer war nicht richtig (der Statustext blockiert
 * nichts, und ton() kehrt wegen RUECKMELDUNG=false sofort zurueck), ihre
 * BEOBACHTUNG aber sehr wohl - und der gemessene Befund ist schlimmer als
 * vermutet: Ruft sie und schweigt, laeuft die Aufnahme bis
 * AUFNAHME_MAX_MS, also **30 Sekunden**, und danach geht nicht einmal
 * etwas an den Server. Die serverseitige Rueckfrage vom 05.09. kann in
 * diesem Fall gar nicht greifen, weil sie eine angekommene Aufnahme
 * voraussetzt.
 *
 * WARUM FESTE DATEIEN UND NICHT VOM SERVER: Der ganze Zweck ist, dass es
 * SOFORT kommt. Ein Server-Aufruf kostet genau die fuenf Sekunden, die
 * hier eingespart werden sollen - und ohne Netz gaebe es gar nichts.
 *
 * DIE VERTONUNG STAMMT AUS DEM BETRIEBSWEG: dieselbe Stimme, dieselben
 * voice_settings, durch dasselbe clean_for_speech - erzeugt mit festem
 * Seed, damit die eingebaute Fassung reproduzierbar ist. Die Betonung
 * ("Was WOLLEN Sie.") hat Doreen am 07.09. aus 16 Hoerproben gewaehlt.
 *
 * ROTATION: Dieselbe Wendung jedes Mal nutzt sich ab - dieselbe Lektion
 * wie bei der Anrede und den Abschluss-Wendungen.
 */
object Rueckfrage {

    /** So viele Dateien liegen in assets (rueckfrage_0..6.mp3). */
    private const val ANZAHL = 7
    private const val FELD = "rueckfrage_nr"
    private const val PREFS = "jarvis"

    /**
     * Spielt die naechste Rueckfrage ab und wartet, bis sie fertig ist.
     *
     * BLOCKIEREND MIT ABSICHT: Waehrend sie laeuft, darf nicht aufgenommen
     * werden - sonst nimmt Jarvis seine eigene Stimme als "Frage" auf.
     * Der Aufrufer verwirft danach den Mikrofonpuffer.
     *
     * DER FOKUS WIRD WIEDER FREIGEGEBEN, obwohl gleich darauf die Antwort
     * folgt: Schweigt sie auch nach der Rueckfrage, kommt gar keine
     * Antwort - und ein haengender Fokus liesse die Musik dauerhaft leise.
     * Der Preis ist ein kurzer Lautstaerkesprung dazwischen; das ist der
     * kleinere Schaden.
     *
     * Ein Fehlschlag ist kein Beinbruch: Dann kommt eben keine Rueckfrage,
     * und die Aufnahme laeuft wie bisher weiter.
     */
    fun abspielen(ctx: Context): Boolean {
        val nr = naechsteNummer(ctx)
        var mp: MediaPlayer? = null
        return try {
            Sprachausgabe.fokusAnfordern(ctx)
            val fd = ctx.assets.openFd("rueckfrage_$nr.mp3")
            val fertig = CountDownLatch(1)
            val player = MediaPlayer()
            mp = player
            player.setAudioAttributes(Sprachausgabe.ATTRIBUTE)
            player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            player.setOnCompletionListener { fertig.countDown() }
            player.setOnErrorListener { _, _, _ -> fertig.countDown(); true }
            player.prepare()
            player.start()
            // Obergrenze, damit ein haengender Player das Zuhoeren nicht
            // dauerhaft blockiert - die laengste Wendung dauert rund 3 s.
            fertig.await(6, TimeUnit.SECONDS)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try { mp?.release() } catch (_: Throwable) {}
            Sprachausgabe.fokusFreigeben(ctx)
        }
    }

    /** Reihum, damit sich keine Wendung abnutzt. */
    private fun naechsteNummer(ctx: Context): Int {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val nr = p.getInt(FELD, 0) % ANZAHL
            p.edit().putInt(FELD, (nr + 1) % ANZAHL).apply()
            nr
        } catch (_: Throwable) {
            0
        }
    }
}
