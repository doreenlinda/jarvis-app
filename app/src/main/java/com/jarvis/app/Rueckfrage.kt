package com.jarvis.app

import android.content.Context
import android.media.MediaPlayer
import java.util.Calendar
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
 *
 * STAND 09.09.2026: zehn Plaetze. Doreen hat vier Wendungen ergaenzt
 * und dabei je drei Hoerproben entschieden - die mit Anrede klingen
 * bewusst RUHIGER als die uebrigen (ohne Ausrufezeichen, ohne Komma).
 * Erzeugt werden die Dateien von bau_rueckfragen.py im Orchestrator.
 */
object Rueckfrage {

    /** Rotationsplaetze 0..9. Platz 8 ist die Begruessung (zwei Dateien). */
    private const val ANZAHL = 10
    private const val BEGRUESSUNG = 8
    /** Bis zu dieser Stunde "Guten Morgen", ab ABEND_AB "Guten Abend". */
    private const val MORGEN_BIS = 11
    private const val ABEND_AB = 18
    private const val FELD = "rueckfrage_nr"
    private const val PREFS = "jarvis"

    /**
     * Welche Asset-Datei gehoert zu diesem Platz - oder null zum Ueberspringen.
     *
     * REINE FUNKTION MIT ABSICHT, damit der Cloud-Build sie prueft: Kotlin
     * laesst sich auf dem Laptop nicht ausfuehren, und was hier falsch ist,
     * faellt sonst erst auf ihrem Handy auf.
     *
     * DIE BEGRUESSUNG FOLGT DER REGEL, die im Projekt seit dem 06.07.2026
     * gilt (persona.txt, _current_time_block): bis 11 Uhr "Guten Morgen",
     * ab 18 Uhr "Guten Abend", DAZWISCHEN GAR KEINE Begruessung. Mittags
     * wird der Platz deshalb uebersprungen, statt ein "Guten Morgen" um
     * drei Uhr nachmittags zu riskieren.
     */
    fun dateiFuer(nr: Int, stunde: Int): String? {
        if (nr != BEGRUESSUNG) return "rueckfrage_$nr.mp3"
        return "rueckfrage_8_morgen.mp3"  // SABOTAGE
    }

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
        // Hoechstens EIN Platz kann uebersprungen werden (die Begruessung
        // ausserhalb ihrer Tageszeit), deshalb genuegt ein zweiter Zug.
        // Der Rueckfall faengt einen durcheinandergeratenen Zaehler ab -
        // lieber eine Wendung doppelt als gar keine Rueckfrage.
        val stunde = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val name = dateiFuer(naechsteNummer(ctx), stunde)
            ?: dateiFuer(naechsteNummer(ctx), stunde)
            ?: "rueckfrage_0.mp3"
        var mp: MediaPlayer? = null
        return try {
            Sprachausgabe.fokusAnfordern(ctx)
            val fd = ctx.assets.openFd(name)
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
