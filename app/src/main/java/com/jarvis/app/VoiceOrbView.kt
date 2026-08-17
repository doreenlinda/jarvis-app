package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Der Voice Orb: ein leuchtender Ring in Doreens Terracotta-Orange, der
 * zeigt, was Jarvis gerade tut.
 *
 * VARIANTE B ("Ring"), von ihr am 17.08.2026 aus drei Entwuerfen gewaehlt -
 * offener Ring, innen dunkel, ruhiger und technischer als ein voller Ball.
 * Die Vorschau lag als "Jarvis Voice Orb.html" auf ihrem Desktop; die
 * Zahlen hier (Groessen, Tempi, Deckkraft) sind daraus uebernommen, damit
 * das Gebaute dem Gezeigten entspricht.
 *
 * KEIN Weichzeichner-Filter: Das Leuchten entsteht aus einem weichen
 * RadialGradient. BlurMaskFilter braeuchte Software-Rendering fuer die
 * ganze View - auf einem Bildschirm, der bei jedem Zuruf animiert, ist das
 * die teurere Loesung.
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

    private val orange = Color.rgb(232, 114, 44)   // #E8722C, wie im Dashboard
    private val hell = Color.rgb(255, 176, 92)     // #FFB05C

    private val scheinPinsel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innenPinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    /** basis = Ruhegroesse, puls = Ausschlag, tempo = Geschwindigkeit,
     *  glow = Staerke des Aussenscheins. Werte aus der Vorschau. */
    private data class Form(
        val basis: Float, val puls: Float, val tempo: Float, val glow: Float,
    )

    private val formen = mapOf(
        OrbZustand.AUS to Form(0.28f, 0f, 0f, 0.10f),
        OrbZustand.RUHE to Form(0.30f, 0.045f, 0.55f, 0.55f),
        OrbZustand.LAUSCHEN to Form(0.42f, 0.14f, 1.7f, 0.95f),
        OrbZustand.DENKEN to Form(0.36f, 0.06f, 3.6f, 0.70f),
        OrbZustand.SPRECHEN to Form(0.46f, 0.20f, 2.4f, 1.0f),
        // Fehler: still stehender, matter Ring. Bewusst OHNE Puls - ein
        // pulsierender Orb neben einer Fehlermeldung liest sich wie
        // "alles in Ordnung".
        OrbZustand.FEHLER to Form(0.30f, 0f, 0f, 0.25f),
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (start == 0L) start = System.nanoTime()
        val t = (System.nanoTime() - start) / 1_000_000_000f

        val f = form()
        val cx = width / 2f
        val cy = height / 2f
        val max = min(width, height) / 2f
        val r = max * amplitude(t)
        if (r <= 0f) return

        // Aussenschein
        val scheinR = r * 2.5f
        if (scheinR > 0f) {
            scheinPinsel.shader = RadialGradient(
                cx, cy, scheinR,
                intArrayOf(
                    Color.argb((0.34f * f.glow * 255).toInt(), 232, 114, 44),
                    Color.argb((0.13f * f.glow * 255).toInt(), 232, 114, 44),
                    Color.argb(0, 232, 114, 44),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, scheinR, scheinPinsel)
        }

        // Der Ring selbst
        ringPinsel.color = hell
        ringPinsel.alpha = if (zustand == OrbZustand.AUS ||
            zustand == OrbZustand.FEHLER
        ) 110 else 242
        ringPinsel.strokeWidth = (r * 0.12f)
            .coerceAtLeast(5f * resources.displayMetrics.density)
        canvas.drawCircle(cx, cy, r, ringPinsel)

        // Zarter Innenring - er gibt dem offenen Ring Tiefe.
        innenPinsel.color = orange
        innenPinsel.alpha = if (zustand == OrbZustand.AUS) 40 else 90
        canvas.drawCircle(cx, cy, r * 0.62f, innenPinsel)

        if (f.puls > 0f && isShown) postInvalidateOnAnimation()
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
