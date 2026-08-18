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
import kotlin.math.min
import kotlin.math.sin

/**
 * Der Voice Orb: eine leuchtende Glaskugel in Doreens Terracotta-Orange,
 * die zeigt, was Jarvis gerade tut.
 *
 * VARIANTE B ("kraeftiger"), von ihr am 18.08.2026 gewaehlt - nach einer
 * Stock-Aufnahme, die sie als Vorlage geschickt hat. Die Datei selbst
 * wandert NICHT in die App (Lizenz); nachgebaut ist allein der Eindruck,
 * in Zeichencode wie zuvor.
 *
 * WAS DIE VORLAGE AUSMACHT - und was der erste Anlauf falsch hatte:
 * Der helle Schimmer ist dort ein BAND, das an beiden Enden weich auf null
 * ausblendet und am Rand entlangfliesst. Mein erster Entwurf zeichnete
 * dafuer einen Bogen mit runden Kappen; der endet abrupt, und Doreens
 * Befund war treffend: "wirkt wie ein statisches kurzes Stueck Reifen, im
 * Reifen". Deshalb ist der Schimmer heute ein WINKEL-VERLAUF (siehe
 * glanz()) - in der Mitte hell, zu beiden Seiten auf null, ohne Kanten.
 * Mass genommen an Sekunde 5,00 der Vorlage, die sie dafuer benannt hat.
 *
 * GROESSE: Die Kugel fuellt fast die Flaeche (230dp statt 180dp). Moeglich
 * wurde das, weil der Zugangsdaten-Knopf auf ihren Vorschlag nach unten
 * gewandert ist. Der Puls muss trotzdem hineinpassen - der groesste Wert
 * (sprechen: .80 + .155) liegt bei .955 und stoesst gerade nicht an.
 *
 * KEIN Weichzeichner-Filter: Das Leuchten entsteht aus Verlaeufen.
 * BlurMaskFilter braeuchte Software-Rendering fuer die ganze View - auf
 * einem Bildschirm, der bei jedem Zuruf animiert, ist das die teurere
 * Loesung.
 *
 * AKKU: Die Animation laeuft NUR, solange die View sichtbar am Fenster
 * haengt (siehe onWindowVisibilityChanged/onDetachedFromWindow). Sie sitzt
 * in der MainActivity, die Doreen selten offen hat - eine dauerhaft
 * zeichnende View waere hier reine Verschwendung. Der Weckwort-Dienst
 * laeuft davon voellig unberuehrt weiter.
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
    private val glanzPinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dreher = Matrix()

    /** basis = Ruhegroesse, puls = Ausschlag, tempo = Geschwindigkeit,
     *  glow = Staerke des Aussenscheins, dreh = wie schnell der Schimmer
     *  um den Rand wandert. Werte aus der Vorschau, die sie beurteilt hat. */
    private data class Form(
        val basis: Float, val puls: Float, val tempo: Float,
        val glow: Float, val dreh: Float,
    )

    private val formen = mapOf(
        OrbZustand.AUS to Form(0.64f, 0f, 0f, 0.10f, 0f),
        OrbZustand.RUHE to Form(0.70f, 0.025f, 0.55f, 0.45f, 0.18f),
        OrbZustand.LAUSCHEN to Form(0.78f, 0.090f, 1.7f, 1.0f, 0.55f),
        OrbZustand.DENKEN to Form(0.73f, 0.040f, 3.6f, 0.62f, 2.1f),
        OrbZustand.SPRECHEN to Form(0.80f, 0.155f, 2.4f, 0.95f, 0.75f),
        // Fehler: still stehende, matte Kugel. Bewusst OHNE Puls und ohne
        // wandernden Schimmer - ein lebhafter Orb neben einer Fehlermeldung
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
    private fun schimmer(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 222, 190)

    /**
     * Ein Winkel-Verlauf, dessen Startpunkt frei liegt. SweepGradient
     * beginnt in Android immer bei 3 Uhr - gedreht wird deshalb ueber eine
     * Matrix auf dem Shader. (Im Web erledigt das der Startwinkel von
     * createConicGradient; das ist der einzige nennenswerte Unterschied
     * zwischen der Vorschau und diesem Code.)
     */
    private fun sweep(
        cx: Float, cy: Float, farben: IntArray, stufen: FloatArray, startRad: Float,
    ): SweepGradient {
        val s = SweepGradient(cx, cy, farben, stufen)
        dreher.reset()
        dreher.setRotate((startRad * 180f / PI.toFloat()), cx, cy)
        s.setLocalMatrix(dreher)
        return s
    }

    /** Der ungleich verteilte Lichtsaum am Rand der Kugel. */
    private fun saum(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        breite: Float, dreh: Float, grund: Float, spitze: Float,
    ) {
        saumPinsel.shader = sweep(
            cx, cy,
            intArrayOf(
                hell(spitze), orange(grund), orange(grund * 0.35f),
                hell(spitze * 0.85f), orange(grund * 0.35f), orange(grund),
                hell(spitze),
            ),
            floatArrayOf(0f, 0.13f, 0.30f, 0.50f, 0.70f, 0.87f, 1f),
            dreh,
        )
        saumPinsel.strokeWidth = breite
        canvas.drawCircle(cx, cy, r - breite / 2f, saumPinsel)
    }

    /**
     * DER SCHIMMER - die Stelle, um die es Doreen ging.
     * In der Mitte hell, zu beiden Enden auf null. Damit hat er keine
     * Kanten; ein Bogen mit runden Kappen haette welche.
     */
    private fun glanz(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        breite: Float, mitte: Float, weite: Float, deckung: Float,
    ) {
        val spanne = ((weite * 2f) / (2f * PI.toFloat())).coerceAtMost(0.999f)
        glanzPinsel.shader = sweep(
            cx, cy,
            intArrayOf(
                schimmer(0f), schimmer(deckung * 0.22f), schimmer(deckung * 0.72f),
                schimmer(deckung), schimmer(deckung * 0.72f), schimmer(deckung * 0.22f),
                schimmer(0f), schimmer(0f),
            ),
            floatArrayOf(
                0f, spanne * 0.22f, spanne * 0.40f, spanne * 0.50f,
                spanne * 0.62f, spanne * 0.80f, spanne, 1f,
            ),
            mitte - weite,
        )
        glanzPinsel.strokeWidth = breite
        canvas.drawCircle(cx, cy, radius, glanzPinsel)
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
        val dreh = -1.2f + t * f.dreh
        // Die Helligkeit traegt den Zustand mit: Bei einer Kugel, die die
        // Flaeche fuellt, kann die Groesse allein ihn nicht mehr zeigen.
        val licht = 0.55f + 0.45f * f.glow

        // AUSSENSCHEIN - und der Grund fuer die Begrenzung:
        // Mit r * 2.9 reichte der Schein ueber die ganze Flaeche hinaus.
        // Die View ist rechteckig, der Schein hellte sie bis in die Ecken
        // auf - auf dem fast schwarzen App-Grund war der Orb dadurch ein
        // sichtbares helleres RECHTECK statt einer frei schwebenden Kugel
        // (Doreens Screenshot vom 18.08.2026). Deshalb endet der Schein
        // nie spaeter als die halbe kurze Kante: Dort ist er auf null,
        // und die Ecken bleiben schwarz. Die Begrenzung muss an der VIEW
        // haengen, nicht am Radius - beim Sprechen waechst die Kugel, und
        // ein fester Faktor liefe wieder hinaus.
        val scheinR = (r * 1.5f).coerceAtMost(min(width, height) / 2f)
        scheinPinsel.shader = RadialGradient(
            cx, cy, scheinR,
            intArrayOf(orange(0.34f * f.glow), orange(0.13f * f.glow), orange(0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, scheinR, scheinPinsel)

        // Der Glaskoerper: innen fast schwarz, zum Rand hin ein warmer Hauch.
        koerperPinsel.shader = RadialGradient(
            cx - r * 0.30f, cy - r * 0.34f, r * 1.30f,
            intArrayOf(
                Color.argb(209, 78, 46, 32),
                Color.argb(219, 34, 20, 15),
                Color.argb(230, 16, 9, 8),
                Color.argb(158, 120, 52, 24),
            ),
            floatArrayOf(0f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, koerperPinsel)

        val breite = (r * 0.075f).coerceAtLeast(5f * resources.displayMetrics.density)
        saum(
            canvas, cx, cy, r, breite, dreh,
            if (matt) 0.40f else 1f * licht,
            if (matt) 0.45f else 1f * licht,
        )

        if (!matt) {
            val mitteGlanz = r - breite * 0.5f
            // Breiter, sehr heller Schimmer - Mass genommen an t = 5,00 s.
            glanz(canvas, cx, cy, mitteGlanz, breite * 1.5f, dreh - 2.4f, 0.95f, 0.95f * licht)
            // Ein zweiter, schwaecherer weiter hinten - die Vorlage hat
            // ebenfalls mehrere Glanzstellen zugleich.
            glanz(canvas, cx, cy, mitteGlanz, breite * 1.15f, dreh + 1.0f, 0.55f, 0.34f * licht)
        }

        // Weiterzeichnen, solange sich etwas bewegt: Der Schimmer wandert
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
}
