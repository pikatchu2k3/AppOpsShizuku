package de.minipc.appopsshizuku.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import de.minipc.appopsshizuku.R
import de.minipc.appopsshizuku.appops.DesignSettings

/**
 * Ruhiger Sternenhimmel als Hintergrund: Grundfarbe, ein weicher Lichtschein oben und zufaellig verteilte
 * Punkte (fester Seed, damit sie bei jedem Start gleich liegen).
 *
 * Animiert (siehe [StarMotion]): die Sterne funkeln, treiben sehr langsam nach oben, der Lichtschein atmet,
 * und gelegentlich zieht eine Sternschnuppe durch. Damit das keinen Akku frisst:
 *  - es laeuft nur, solange die View SICHTBAR ist (Fenster sichtbar, Bildschirm an, Activity vorne),
 *  - hoechstens ~30 Bilder pro Sekunde,
 *  - nichts wird pro Bild neu angelegt (keine Speicherbereinigung durch die Animation),
 *  - es stoppt bei Energiesparmodus, wenn die Systemanimationen abgeschaltet sind (Entwickleroptionen
 *    "Animationsdauer" = aus / Bedienungshilfen "Animationen entfernen") und wenn der Schalter in den
 *    Einstellungen aus ist. Dann steht der Himmel still, genau wie fruehere Versionen ihn zeigten.
 */
class StarfieldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val starColor = ContextCompat.getColor(context, R.color.space_star)
    private val starBaseAlpha = starColor ushr 24
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = starColor }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = starColor or 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private val bg = ContextCompat.getColor(context, R.color.space_bg)
    private val glow = ContextCompat.getColor(context, R.color.space_glow)

    private var motion: StarMotion? = null
    private val streak = StarMotion.Streak()

    /** Bezugspunkt der Zeit: die Animation ist eine Funktion von (jetzt - epoch), Pausieren kostet nichts. */
    private val epoch = SystemClock.uptimeMillis()
    private var animating = false
    private var framesSinceCheck = 0

    private val tick = Runnable { invalidate() }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(DesignSettings.PREFS, Context.MODE_PRIVATE)
    // Muss als Feld gehalten werden: SharedPreferences verweist nur schwach auf Listener
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DesignSettings.KEY_STAR_ANIMATION) post { updateAnimation() }
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateAnimation()
    }
    private var receiverRegistered = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        motion = StarMotion(w.toFloat(), h.toFloat(), resources.displayMetrics.density)
        glowPaint.shader = RadialGradient(
            w * 0.3f, 0f, maxOf(w, h) * 0.7f, glow, 0x00000000, Shader.TileMode.CLAMP
        )
        updateAnimation()
    }

    // ---------------------------------------------------------------- Lebenszyklus der Animation

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context.applicationContext, powerReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (receiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    /** Laeuft die Animation gerade? Nur wenn sichtbar UND erlaubt (Schalter, Energiesparmodus, Systemanimationen). */
    private fun updateAnimation() {
        val want = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            width > 0 && height > 0 && motionAllowed()
        if (want && !animating) {
            animating = true
            framesSinceCheck = 0
            invalidate()
        } else if (!want && animating) {
            stopAnimation()
        }
    }

    private fun stopAnimation() {
        if (!animating) return
        animating = false
        removeCallbacks(tick)
        motion?.staticPose()
        invalidate()
    }

    private fun motionAllowed(): Boolean {
        if (!DesignSettings.isStarAnimationEnabled(context)) return false
        // Systemweit abgeschaltete Animationen (Bedienungshilfen "Animationen entfernen") achten
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale <= 0f) return false
        val power = context.getSystemService(PowerManager::class.java)
        return power == null || !power.isPowerSaveMode
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val m = motion
        canvas.drawColor(bg)
        if (m == null) return

        val t = (SystemClock.uptimeMillis() - epoch) / 1000.0
        if (animating) m.update(t)

        glowPaint.alpha = if (animating) (255 * m.glow(t)).toInt() else 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)

        for (i in 0 until m.count) {
            starPaint.alpha = (starBaseAlpha * m.brightness[i]).toInt()
            canvas.drawCircle(m.x[i], m.y[i], m.radius[i], starPaint)
        }

        if (animating) {
            m.streak(t, streak)
            if (streak.active) drawStreak(canvas)
            // Naechstes Bild frueh genug planen; alle paar Sekunden pruefen, ob die Animation noch erlaubt ist
            if (++framesSinceCheck >= RECHECK_FRAMES) {
                framesSinceCheck = 0
                updateAnimation()
            }
            if (animating) postOnAnimationDelayed(tick, FRAME_DELAY_MS)
        }
    }

    /** Sternschnuppe: Schwanz aus kurzen, zum Kopf hin heller werdenden Strichen, dazu ein heller Kopf. */
    private fun drawStreak(canvas: Canvas) {
        val s = streak
        for (k in 0 until STREAK_SEGMENTS) {
            val p0 = k / STREAK_SEGMENTS.toFloat()
            val p1 = (k + 1) / STREAK_SEGMENTS.toFloat()
            streakPaint.alpha = (255f * s.alpha * p1 * p1 * 0.85f).toInt()
            canvas.drawLine(
                s.tailX + (s.headX - s.tailX) * p0, s.tailY + (s.headY - s.tailY) * p0,
                s.tailX + (s.headX - s.tailX) * p1, s.tailY + (s.headY - s.tailY) * p1,
                streakPaint
            )
        }
        starPaint.alpha = (255f * s.alpha).toInt()
        canvas.drawCircle(s.headX, s.headY, 1.7f * resources.displayMetrics.density, starPaint)
    }

    private companion object {
        /** ~30 Bilder pro Sekunde: fuer langsames Funkeln reicht das, und es halbiert die Arbeit gegenueber 60. */
        const val FRAME_DELAY_MS = 26L
        const val RECHECK_FRAMES = 90
        const val STREAK_SEGMENTS = 10
    }
}
