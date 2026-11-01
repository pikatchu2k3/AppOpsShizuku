package de.minipc.appopsshizuku.ui

import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

/** Faerbt die markierten Zeichen mit einem diagonalen Farbverlauf (Kopfzeilen-Titel). */
class GradientSpan(private val from: Int, private val to: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val size = tp.textSize
        tp.shader = LinearGradient(0f, 0f, size, size, from, to, Shader.TileMode.CLAMP)
    }
}
