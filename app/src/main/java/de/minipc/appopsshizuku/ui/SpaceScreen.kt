package de.minipc.appopsshizuku.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Gemeinsamer Rahmen fuer die Unterseiten im Design "Sternenhimmel": legt den Sternenhimmel HINTER den
 * bestehenden Inhalt. Die Seite behaelt ihr Layout; der Hintergrund ist damit ueberall gleich.
 */
object SpaceScreen {

    /**
     * Ersetzt den Inhalt der Activity (nach setContentView) durch: Sternenhimmel + bisheriger Inhalt.
     * Die Toolbar-Hintergrundfarbe wird transparent, damit der Sternenhimmel durchscheint.
     */
    fun apply(activity: AppCompatActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        content.removeView(root)
        val stack = FrameLayout(activity)
        stack.addView(
            StarfieldView(activity),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        stack.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(stack, 0)
        // Toolbar und Tabs transparent, damit der Himmel durchscheint
        transparentChrome(root)
    }

    private fun transparentChrome(v: View) {
        if (v is com.google.android.material.appbar.AppBarLayout ||
            v is com.google.android.material.appbar.MaterialToolbar ||
            v is com.google.android.material.tabs.TabLayout
        ) {
            v.setBackgroundColor(0x00000000)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) transparentChrome(v.getChildAt(i))
    }
}
