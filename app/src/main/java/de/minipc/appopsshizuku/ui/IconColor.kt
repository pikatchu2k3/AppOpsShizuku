package de.minipc.appopsshizuku.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/** Hauptfarbe eines App-Icons (klein gerendert, damit es schnell geht). */
object IconColor {
    private const val SIZE = 24

    fun of(icon: Drawable?): Int? {
        icon ?: return null
        return runCatching {
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            copy.setBounds(0, 0, SIZE, SIZE)
            copy.draw(c)
            val px = IntArray(SIZE * SIZE)
            bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            bmp.recycle()
            CardColors.dominant(px)
        }.getOrNull()
    }
}
