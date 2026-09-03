package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Der Voice Orb: eine leuchtende Glaskugel in Doreens Terracotta-Orange,
 * die zeigt, was Jarvis gerade tut.
 *
 * STAND 03.09.2026 - "Fassung 1, so ruhig wie 2, Orange-Stufe C", von ihr
 * aus einer Vorschau gewaehlt. Grundlage war ihr eigenes Referenz-Video,
 * das sie ein zweites Mal geschickt hat, nachdem zwei Anlaeufe daneben
 * lagen. Die Datei selbst wandert NICHT in die App (Lizenz); nachgebaut
 * ist allein der Eindruck, in Zeichencode.
 *
 * WAS DAS VIDEO ZEIGT - und warum der Orb vorher "wie ein Reifen" wirkte:
 * Dort ist keine massive Kugel mit Struktur darin zu sehen, sondern eine
 * duennwandige Blase. Das Innere ist fast schwarz. Die ganze Bewegung
 * sitzt im LICHTSAUM am Rand, und der ist UNGLEICHMAESSIG: helle, fast
 * weisse Verdichtungen, dazwischen Stellen, an denen er beinahe
 * verschwindet - und genau die wandern langsam um den Umfang.
 *
 * Der Saum davor war ein SweepGradient mit FESTEN Stufen: ein starres
 * Muster, das als Ganzes rotiert. Ein starres Muster, das sich dreht, IST
 * ein Reifen. Heute ist der Saum die Summe mehrerer Glocken, deren
 * Zentren mit VERSCHIEDENEN Geschwindigkeiten wandern, eine davon
 * gegenlaeufig - dadurch wiederholt sich das Bild nie, und es wirkt
 * gerollt statt gedreht. Das ist der ganze Unterschied.
 *
 * ZWEI VERWORFENE ANLAEUFE, damit sie niemand wiederholt:
 * 1. Ellipsen, die geradlinig durch die Kugel wandern - ihr Urteil: "als
 *    wuerden sich Planeten auf elliptischen Umlaufbahnen bewegen".
 * 2. Punkte und Sterne auf der Kugeloberflaeche, sphaerisch korrekt
 *    gestaucht, dazu ein festes Spitzlicht und ein Randsaum - technisch
 *    richtig, trotzdem abgelehnt: "sieht aus wie der Planet Saturn mit
 *    Umlaufbahn". Ihre Vorgabe danach: "Es soll nur aussehen wie Glas
 *    ohne stoerende Extras."
 * Deshalb gibt es hier KEIN Spitzlicht, KEINE Punkte und kein Muster im
 * Inneren - nur den Saum und zwei sehr schwache Reflexboegen.
 *
 * FARBE: Stufe C von dreien. Das kraeftige Orange (232,114,44) und das
 * Hellorange (255,176,92) sind unveraendert die Farben der App; verschoben
 * wurde der Aufhellton, der den gelben Eindruck machte - von cremig
 * (255,222,190) auf (255,182,120). Dazu liegen die Schwellen hoch: Der
 * groesste Teil des Saums bleibt im kraeftigen Orange, das Helle blitzt
 * nur an den staerksten Stellen durch.
 *
 * GROESSE: Die Kugel fuellt fast die Flaeche (230dp). Der Puls muss
 * hineinpassen - der groesste Wert (sprechen: .80 + .155) liegt bei .955
 * und stoesst gerade nicht an.
 *
 * KEIN Weichzeichner-Filter: Das Leuchten entsteht aus Verlaeufen.
 * BlurMaskFilter braeuchte Software-Rendering fuer die ganze View.
 *
 * AKKU: Die Animation laeuft NUR, solange die View sichtbar am Fenster
 * haengt. Der Weckwort-Dienst laeuft davon voellig unberuehrt weiter.
 */
class VoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val scheinPinsel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val koerperPinsel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val saumPinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bogenPinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dreher = Matrix()

    /** basis = Ruhegroesse, puls = Ausschlag, tempo = Geschwindigkeit des
     *  Pulses, glow = Staerke des Aussenscheins, dreh = wie schnell die
     *  Lichter um den Rand wandern (Bogenmass je Sekunde).
     *
     *  Die dreh-Werte stammen aus der Vorschau, die sie beurteilt hat -
     *  sie sind bewusst LANGSAMER als vorher, weil ihre Wahl "Fassung 1,
     *  so ruhig wie 2" lautete. Denken bleibt gut dreimal so schnell wie
     *  Ruhe, damit der Zustand ablesbar bleibt. */
    private data class Form(
        val basis: Float, val puls: Float, val tempo: Float,
        val glow: Float, val dreh: Float,
    )

    private val formen = mapOf(
        OrbZustand.AUS to Form(0.64f, 0f, 0f, 0.10f, 0f),
        OrbZustand.RUHE to Form(0.70f, 0.025f, 0.55f, 0.45f, 0.214f),
        OrbZustand.LAUSCHEN to Form(0.78f, 0.090f, 1.7f, 1.0f, 0.351f),
        OrbZustand.DENKEN to Form(0.73f, 0.040f, 3.6f, 0.62f, 0.721f),
        OrbZustand.SPRECHEN to Form(0.80f, 0.155f, 2.4f, 0.95f, 0.468f),
        // Fehler: still stehende, matte Kugel. Bewusst OHNE Puls und ohne
        // wandernde Lichter - ein lebhafter Orb neben einer Fehlermeldung
        // liest sich wie "alles in Ordnung".
        OrbZustand.FEHLER to Form(0.66f, 0f, 0f, 0.22f, 0f),
    )

    private var zustand: String = OrbZustand.AUS
    private var start = 0L

    fun setzeZustand(neu: String) {
        if (neu == zustand) return
        zustand = neu
        invalidate()
    }

    private fun form(): Form = formen[zustand] ?: formen.getValue(OrbZustand.RUHE)

    /** Mehrere Sinuskurven uebereinander, damit "Sprechen" nicht wie ein
     *  Metronom wirkt, sondern unregelmaessig wie eine Stimme. */
    private fun amplitude(t: Float): Float {
        val f = form()
        if (f.puls == 0f) return f.basis
        if (zustand == OrbZustand.DENKEN) {
            return f.basis + f.puls * (0.5f + 0.5f * sin(t * f.tempo))
        }
        val v = sin(t * f.tempo) * 0.6f +
            sin(t * f.tempo * 1.9f + 1.1f) * 0.28f +
            sin(t * f.tempo * 3.3f + 2.2f) * 0.12f
        return f.basis + f.puls * v
    }

    private fun orange(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 232, 114, 44)
    private fun hell(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 176, 92)

    /** Der Aufhellton. NICHT mehr cremig (255,222,190) - der machte den
     *  gelben Eindruck, den sie am 03.09.2026 beanstandet hat. */
    private fun spitze(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 182, 120)

    /**
     * Eine Glocke um einen Winkel herum, ueber die Kreisgrenze hinweg
     * richtig gerechnet. Ohne die Normierung auf plus/minus PI haette
     * jedes Licht bei 3 Uhr eine harte Kante.
     */
    private fun glocke(theta: Float, mitte: Float, breite: Float): Float {
        var d = theta - mitte
        val zwei = 2f * PI.toFloat()
        while (d > PI.toFloat()) d -= zwei
        while (d < -PI.toFloat()) d += zwei
        return exp(-(d * d) / (2f * breite * breite))
    }

    /**
     * DIE STELLE, UM DIE ES GING.
     *
     * Der Saum entsteht aus drei Lichtern, die mit verschiedenen Tempi um
     * den Rand wandern - das dritte gegenlaeufig. Weil sich ihre Perioden
     * nicht teilen, wiederholt sich das Bild praktisch nie; genau das
     * unterscheidet eine rollende Kugel von einem rotierenden Ring.
     *
     * Gezeichnet wird in ZWEI Durchgaengen statt in vielen Segmenten:
     * ein duenner Saum ueberall, darueber ein breiter, der nur an den
     * hellen Stellen sichtbar wird (Alpha mit w hoch drei). Zusammen
     * ergibt das die wandernde Verdickung aus dem Video - mit zwei
     * Zeichenschritten statt zweihundert, was auf dem Handy zaehlt.
     */
    private fun saumFarben(t: Float, dreh: Float, licht: Float, nurSpitzen: Boolean): IntArray {
        val zwei = 2f * PI.toFloat()
        val v = t * dreh
        val m1 = v
        val m2 = -v * 0.62f + 2f
        val m3 = v * 1.73f + 4f
        val k = licht * 0.88f
        val farben = IntArray(STUFEN + 1)
        for (i in 0..STUFEN) {
            val theta = (i.toFloat() / STUFEN) * zwei
            var w = 0.06f +
                0.80f * glocke(theta, m1, 0.58f) +
                0.55f * glocke(theta, m2, 0.44f) +
                0.42f * glocke(theta, m3, 0.34f)
            if (w > 1f) w = 1f
            // Der breite Durchgang bleibt an dunklen Stellen unsichtbar.
            val d = if (nurSpitzen) w * w * w else 1f
            farben[i] = when {
                w > 0.90f -> spitze(min(0.95f, w * k) * d)
                w > 0.68f -> hell(min(0.85f, w * k * 0.95f) * d)
                else -> orange(min(0.70f, w * k * 0.85f) * d)
            }
        }
        return farben
    }

    private fun saum(canvas: Canvas, cx: Float, cy: Float, r: Float, breite: Float, farben: IntArray) {
        saumPinsel.shader = SweepGradient(cx, cy, farben, STELLEN)
        saumPinsel.strokeWidth = breite
        canvas.drawCircle(cx, cy, r - breite / 2f, saumPinsel)
    }

    /**
     * Ein Reflexbogen im Inneren. Sein Kreismittelpunkt ist VERSETZT -
     * dadurch laeuft er ein Stueck weit parallel zum Rand nach innen,
     * so wie die weichen Boegen in der Vorlage. Gezeichnet wird nur der
     * Abschnitt gegenueber dem Versatz; der Rest des Kreises bleibt auf
     * Alpha null und damit unsichtbar, weshalb hier kein Clipping noetig
     * ist (ein Kreis-Clip waere auf Android ohne Antialiasing und wuerde
     * eine treppige Kante hinterlassen).
     */
    private fun innenbogen(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        phi: Float, tiefe: Float, spanne: Float, breite: Float, deckung: Float,
    ) {
        val vx = cx + cos(phi) * r * tiefe
        val vy = cy + sin(phi) * r * tiefe
        val rr = r * (1f - tiefe * 0.55f)
        val anteil = (spanne / (2f * PI.toFloat())).coerceAtMost(0.999f)
        val s = SweepGradient(
            vx, vy,
            intArrayOf(
                hell(0f), hell(deckung * 0.30f), hell(deckung),
                hell(deckung * 0.30f), hell(0f), hell(0f),
            ),
            floatArrayOf(0f, anteil * 0.25f, anteil * 0.5f, anteil * 0.75f, anteil, 1f),
        )
        dreher.reset()
        dreher.setRotate(((phi + PI.toFloat() - spanne / 2f) * 180f / PI.toFloat()), vx, vy)
        s.setLocalMatrix(dreher)
        bogenPinsel.shader = s
        bogenPinsel.strokeWidth = breite
        canvas.drawCircle(vx, vy, rr, bogenPinsel)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (start == 0L) start = System.nanoTime()
        val t = (System.nanoTime() - start) / 1_000_000_000f

        val f = form()
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * amplitude(t)
        if (r <= 0f) return

        val matt = zustand == OrbZustand.AUS || zustand == OrbZustand.FEHLER
        val licht = if (matt) 0.45f else 0.55f + 0.45f * f.glow

        // AUSSENSCHEIN. Deutlich schwaecher als frueher: Im Video liegt die
        // Kugel auf fast schwarzem Grund, und ein kraeftiger Hof machte aus
        // ihr eine braune Scheibe statt einer Blase.
        // Die Begrenzung muss an der VIEW haengen, nicht am Radius - beim
        // Sprechen waechst die Kugel, und ein fester Faktor liefe ueber die
        // Flaeche hinaus. Dann waere der Orb ein sichtbares helleres
        // RECHTECK statt einer frei schwebenden Kugel (Doreens Screenshot
        // vom 18.08.2026).
        val scheinR = (r * 1.45f).coerceAtMost(min(width, height) / 2f)
        scheinPinsel.shader = RadialGradient(
            cx, cy, scheinR,
            intArrayOf(orange(0.10f * f.glow), orange(0.055f * f.glow), orange(0f)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, scheinR, scheinPinsel)

        // Der Glaskoerper: innen fast schwarz, zum Rand hin ein warmer
        // Hauch. Bewusst sehr durchsichtig - eine duennwandige Blase, keine
        // massive Kugel.
        koerperPinsel.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                Color.argb(107, 18, 9, 5),
                Color.argb(92, 22, 11, 6),
                Color.argb(77, 38, 18, 9),
                Color.argb(41, 46, 21, 11),
            ),
            floatArrayOf(0f, 0.66f, 0.92f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, koerperPinsel)

        val dicht = r * 0.046f
        val duenn = (dicht * 0.42f).coerceAtLeast(1.6f * resources.displayMetrics.density)
        saum(canvas, cx, cy, r, duenn, saumFarben(t, f.dreh, licht, false))
        saum(canvas, cx, cy, r, dicht * 1.25f, saumFarben(t, f.dreh, licht, true))

        if (!matt) {
            val v = t * f.dreh
            innenbogen(canvas, cx, cy, r, v + 0.5f, 0.19f, 1.05f, r * 0.030f, 0.15f * licht)
            innenbogen(canvas, cx, cy, r, -v * 0.62f + 2.4f, 0.28f, 0.85f, r * 0.024f, 0.09f * licht)
        }

        // Weiterzeichnen, solange sich etwas bewegt: Die Lichter wandern
        // auch dann, wenn die Kugel nicht pulsiert (Ruhe).
        if ((f.puls > 0f || f.dreh > 0f) && isShown) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            start = 0L
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        start = 0L
    }

    private companion object {
        /** Aufloesung des Saums: 72 Stufen sind 5 Grad - fein genug fuer
         *  die schmalste Glocke (rund 19 Grad breit) und billig genug fuer
         *  zwei Zeichenschritte je Bild. */
        const val STUFEN = 72
        val STELLEN = FloatArray(STUFEN + 1) { it.toFloat() / STUFEN }
    }
}
