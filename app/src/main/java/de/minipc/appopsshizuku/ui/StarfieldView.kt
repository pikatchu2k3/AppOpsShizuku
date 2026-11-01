package de.minipc.appopsshizuku.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import de.minipc.appopsshizuku.R
import kotlin.random.Random

/**
 * Ruhiger Sternenhimmel als Hintergrund: Grundfarbe, ein weicher Lichtschein oben und feste,
 * zufaellig verteilte Punkte (fester Seed, damit sie nicht springen). Keine Animation - kostet
 * keinen Akku.
 */
class StarfieldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val starColor = ContextCompat.getColor(context, R.color.space_star)
    private val starBaseAlpha = starColor ushr 24
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = starColor }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = ContextCompat.getColor(context, R.color.space_bg)
    private val glow = ContextCompat.getColor(context, R.color.space_glow)
    private var stars: FloatArray = FloatArray(0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rnd = Random(42)
        val density = resources.displayMetrics.density
        val count = ((w * h) / (density * density * 2600f)).toInt().coerceIn(40, 260)
        stars = FloatArray(count * 4)
        for (i in 0 until count) {
            stars[i * 4] = rnd.nextFloat() * w
            stars[i * 4 + 1] = rnd.nextFloat() * h
            stars[i * 4 + 2] = (0.6f + rnd.nextFloat() * 1.4f) * density // Radius
            stars[i * 4 + 3] = 0.25f + rnd.nextFloat() * 0.6f // Helligkeit
        }
        glowPaint.shader = RadialGradient(
            w * 0.3f, 0f, maxOf(w, h) * 0.7f, glow, 0x00000000, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        for (i in 0 until stars.size / 4) {
            starPaint.alpha = (starBaseAlpha * stars[i * 4 + 3]).toInt()
            canvas.drawCircle(stars[i * 4], stars[i * 4 + 1], stars[i * 4 + 2], starPaint)
        }
    }
}
