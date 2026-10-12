package de.minipc.appopsshizuku.ui

import com.google.android.material.switchmaterial.SwitchMaterial

/** Unterscheidet programmatische von Nutzer-Aenderungen (Listener feuert synchron im Setter). */
class ToggleGuard {
    private var depth = 0

    fun <T> silently(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }

    /** Fuehrt [block] nur aus, wenn gerade keine programmatische Aenderung laeuft. */
    fun ifUser(block: () -> Unit) {
        if (depth == 0) block()
    }
}

/** Schalter, dessen Listener nur bei echten Fingertipps feuert; [setQuietly] loest nie etwas aus. */
class GuardedSwitch(val view: SwitchMaterial) {
    private val guard = ToggleGuard()

    fun setQuietly(checked: Boolean) = guard.silently { view.isChecked = checked }

    fun onUserToggle(cb: (Boolean) -> Unit) =
        view.setOnCheckedChangeListener { _, checked -> guard.ifUser { cb(checked) } }
}
