package de.minipc.appopsshizuku.ui

/**
 * Farben der Verlaufskarten: links die Hauptfarbe des App-Icons, nach rechts ueber Nachtblau in
 * Petrol. Reine Rechnung auf ARGB-Ints (ohne android.graphics.Color, damit JVM-testbar).
 *
 * Weisser Text muss auf jeder Kartenfarbe lesbar bleiben: die Icon-Farbe wird so weit abgedunkelt,
 * bis der WCAG-Kontrast zu Weiss mindestens [MIN_CONTRAST] betraegt.
 */
object CardColors {

    const val MIN_CONTRAST = 4.5
    /** Mittel- und Endfarbe des Verlaufs (Nachtblau, Petrol) - beide mit Weiss > 7:1. */
    const val MID = 0xFF13305C.toInt()
    const val END = 0xFF0B5560.toInt()
    /** Rueckfall fuer graue/leere Icons. */
    const val FALLBACK = 0xFF2E5F8A.toInt()

    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** RGB -> HSV (h 0..360, s 0..1, v 0..1). */
    fun toHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - Math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun to(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to(r) shl 16) or (to(g) shl 8) or to(b)
    }

    /**
     * Hauptfarbe eines Icons aus seinen Pixeln: Farbton-Mittel der ausreichend deckenden und
     * gesaettigten Pixel (Hintergrund/Weiss/Grau zaehlen nicht). null = keine bunte Farbe gefunden.
     */
    fun dominant(pixels: IntArray): Int? {
        var sx = 0.0; var sy = 0.0; var sw = 0.0; var vSum = 0.0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val hsv = toHsv(p)
            if (hsv[1] < 0.25f || hsv[2] < 0.15f) continue
            val w = (hsv[1] * hsv[2]).toDouble()
            val rad = Math.toRadians(hsv[0].toDouble())
            sx += Math.cos(rad) * w; sy += Math.sin(rad) * w; sw += w; vSum += hsv[2] * w
        }
        if (sw < 1e-6) return null
        val hue = Math.toDegrees(Math.atan2(sy, sx)).let { if (it < 0) it + 360 else it }.toFloat()
        return fromHsv(hue, 0.75f, (vSum / sw).toFloat().coerceIn(0.5f, 0.9f))
    }

    /** Linke Kartenfarbe: Icon-Farbe, so weit abgedunkelt, dass Weiss >= [MIN_CONTRAST] hat. */
    fun cardStart(seed: Int?): Int {
        val base = seed ?: FALLBACK
        val hsv = toHsv(base)
        var s = hsv[1].coerceIn(0.45f, 0.85f)
        if (hsv[1] < 0.1f) s = hsv[1] // grau bleibt grau
        var v = hsv[2].coerceAtMost(0.85f)
        var c = fromHsv(hsv[0], s, v)
        while (contrast(c, WHITE) < MIN_CONTRAST && v > 0.05f) {
            v -= 0.03f
            c = fromHsv(hsv[0], s, v)
        }
        return c
    }

    const val WHITE = 0xFFFFFFFF.toInt()
}
