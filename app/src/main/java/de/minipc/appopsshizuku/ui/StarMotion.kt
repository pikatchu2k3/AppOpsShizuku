package de.minipc.appopsshizuku.ui

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bewegung des Sternenhimmels: Funkeln, langsames Treiben und eine gelegentliche Sternschnuppe.
 *
 * Reine Rechnung ohne Android (unit-testbar) und OHNE Speicherzuweisung pro Bild: [update] fuellt feste
 * Felder, die [StarfieldView] direkt zeichnet. Alles ist eine Funktion der Zeit `t` (Sekunden), nichts wird
 * hochgezaehlt - Pausieren und Fortsetzen kostet nichts, und gleicher Seed ergibt dieselbe Bewegung.
 *
 * Die GRUNDVERTEILUNG der Sterne (Position, Radius, Helligkeit) ist bewusst dieselbe wie im frueheren
 * statischen Himmel (`Random(42)`, gleiche Reihenfolge): ohne Bewegung sieht der Himmel genauso aus wie vorher.
 *
 * @param density Pixel je dp; alle Geschwindigkeiten und Laengen sind in dp angegeben.
 */
class StarMotion(
    private val width: Float,
    private val height: Float,
    private val density: Float,
    seed: Long = SEED
) {
    val count: Int = starCount(width, height, density)

    /** Aktuelle Lage je Stern (Pixel). Nach [update]; vor dem ersten Aufruf die Grundlage. */
    val x = FloatArray(count)
    val y = FloatArray(count)

    /** Radius je Stern (Pixel), fest. */
    val radius = FloatArray(count)

    /** Aktuelle Helligkeit je Stern, 0..1 (Grundhelligkeit mal Funkeln mal Randausblendung). */
    val brightness = FloatArray(count)

    private val x0 = FloatArray(count)
    private val y0 = FloatArray(count)
    private val base = FloatArray(count)
    private val vx = FloatArray(count)       // dp/s
    private val vy = FloatArray(count)       // dp/s (negativ = nach oben)
    private val phase = FloatArray(count)    // 0..1 Perioden
    private val freq = FloatArray(count)     // Hz
    private val amp = FloatArray(count)      // Schwankung der Helligkeit (+-amp/2)

    init {
        // Grundverteilung: wie der fruehere statische Himmel
        val rnd = Random(seed)
        for (i in 0 until count) {
            x0[i] = rnd.nextFloat() * width
            y0[i] = rnd.nextFloat() * height
            radius[i] = (0.6f + rnd.nextFloat() * 1.4f) * density
            base[i] = 0.25f + rnd.nextFloat() * 0.6f
        }
        // Bewegungs-Eigenschaften aus einer zweiten Folge, damit die Grundverteilung unberuehrt bleibt
        val mot = Random(seed xor 0x5DEECE66DL)
        for (i in 0 until count) {
            // Tiefe 0 (fern, klein) .. 1 (nah, gross): Nahe Sterne treiben schneller (Parallaxe)
            val depth = ((radius[i] / density) - 0.6f) / 1.4f
            val speed = 0.8f + depth * 2.6f                         // 0,8 .. 3,4 dp/s
            vy[i] = -speed * (0.8f + mot.nextFloat() * 0.4f)
            vx[i] = speed * 0.25f * (mot.nextFloat() * 2f - 1f)
            phase[i] = mot.nextFloat()
            val sparkle = mot.nextFloat() < SPARKLE_SHARE
            freq[i] = if (sparkle) 0.35f + mot.nextFloat() * 0.45f else 0.06f + mot.nextFloat() * 0.14f
            amp[i] = if (sparkle) 0.9f else 0.15f + mot.nextFloat() * 0.25f
        }
        staticPose()
    }

    /** Ruhelage: Grundposition und Grundhelligkeit, genau wie der fruehere statische Himmel. */
    fun staticPose() {
        for (i in 0 until count) {
            x[i] = x0[i]
            y[i] = y0[i]
            brightness[i] = base[i]
        }
    }

    /** Lage und Helligkeit aller Sterne zur Zeit [tSec] berechnen. */
    fun update(tSec: Double) {
        val fadeBand = EDGE_FADE_DP * density
        for (i in 0 until count) {
            val px = wrap(x0[i] + vx[i] * density * tSec, width.toDouble())
            val py = wrap(y0[i] + vy[i] * density * tSec, height.toDouble())
            x[i] = px.toFloat()
            y[i] = py.toFloat()
            val twinkle = 1f + 0.5f * amp[i] * sin(2.0 * PI * (freq[i] * tSec + phase[i])).toFloat()
            // Am Rand, wo ein Stern auf der Gegenseite wieder auftaucht, ausblenden statt aufploppen
            val edge = min(
                min(px.toFloat(), width - px.toFloat()),
                min(py.toFloat(), height - py.toFloat())
            )
            val fade = if (edge >= fadeBand) 1f else (edge / fadeBand).coerceIn(0f, 1f)
            brightness[i] = (base[i] * twinkle * fade).coerceIn(0f, 1f)
        }
    }

    /** Atmender Lichtschein: Faktor fuer die Deckkraft, 0,85 .. 1,0 (Periode [GLOW_PERIOD_S]). */
    fun glow(tSec: Double): Float =
        (0.925 + 0.075 * sin(2.0 * PI * tSec / GLOW_PERIOD_S)).toFloat()

    /** Sternschnuppe: Kopf, Schwanz und Helligkeit. [active] false = gerade keine. */
    class Streak {
        var active = false
        var headX = 0f
        var headY = 0f
        var tailX = 0f
        var tailY = 0f

        /** 0..1, blendet am Anfang und Ende des Auftritts weich ein und aus. */
        var alpha = 0f
    }

    /**
     * Sternschnuppe zur Zeit [tSec] in [out]. Alle [STREAK_PERIOD_S] Sekunden gibt es ein Zeitfenster; der
     * Auftritt liegt darin um eine feste, vom Seed abhaengige Verschiebung - so vergehen zwischen zwei
     * Sternschnuppen 12 bis 36 Sekunden, sie ueberlappen nie, und es ist keine Zustandsfolge noetig.
     */
    fun streak(tSec: Double, out: Streak) {
        out.active = false
        if (tSec < 0) return
        val n = floor(tSec / STREAK_PERIOD_S).toLong()
        val rnd = Random(seedForStreak(n))
        val start = n * STREAK_PERIOD_S + STREAK_JITTER_MIN_S + rnd.nextDouble() * STREAK_JITTER_SPAN_S
        val local = tSec - start
        if (local < 0 || local >= STREAK_DURATION_S) return

        val p = (local / STREAK_DURATION_S).toFloat()            // 0..1
        // Weg: von einem Punkt im oberen Teil schraeg nach unten
        val angle = Math.toRadians(18.0 + rnd.nextDouble() * 22.0)
        val length = (110f + rnd.nextFloat() * 70f) * density
        val travel = (width * 0.55f)
        val sx = width * (0.05f + rnd.nextFloat() * 0.55f)
        val sy = height * (0.04f + rnd.nextFloat() * 0.38f)
        val dx = kotlin.math.cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        val head = travel * easeOut(p)
        out.headX = sx + dx * head
        out.headY = sy + dy * head
        val tail = min(head, length)
        out.tailX = out.headX - dx * tail
        out.tailY = out.headY - dy * tail
        // weich ein- und ausblenden: Sinus-Huegel ueber den Auftritt
        out.alpha = sin(PI * p).toFloat().coerceIn(0f, 1f)
        out.active = out.alpha > 0.001f
    }

    private fun seedForStreak(n: Long): Long = (n + 1) * 0x9E3779B97F4A7C15uL.toLong() xor 0x5851F42D4C957F2DL

    private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)

    private fun wrap(v: Double, size: Double): Double {
        if (size <= 0) return 0.0
        val r = v % size
        return if (r < 0) r + size else r
    }

    companion object {
        /** Wie bisher: fester Seed, damit der Himmel bei jedem Start gleich aussieht. */
        const val SEED = 42L

        const val SPARKLE_SHARE = 0.12f
        const val EDGE_FADE_DP = 28f
        const val GLOW_PERIOD_S = 14.0

        const val STREAK_PERIOD_S = 24.0
        const val STREAK_JITTER_MIN_S = 3.0
        const val STREAK_JITTER_SPAN_S = 12.0
        const val STREAK_DURATION_S = 1.1

        /** Gleiche Dichte wie der fruehere statische Himmel. */
        fun starCount(width: Float, height: Float, density: Float): Int =
            ((width * height) / (density * density * 2600f)).toInt().coerceIn(40, 260)
    }
}
