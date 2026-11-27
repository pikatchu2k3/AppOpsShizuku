package de.minipc.appopsshizuku.ui

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Hintergrund-Thread und UI-Handler, die mit dem Lebenszyklus von Activity/Fragment enden.
 *
 * Frueher legte jede Activity einen eigenen `Executors.new...()`-Pool an und beendete ihn nur zum Teil
 * (MainActivity nie): bei jedem Neuaufbau (Drehen, Dunkelmodus) blieb ein Pool mit Threads zurueck. Und
 * `handler.post { ... }` lief auch nach `onDestroy` noch gegen eine tote Oberflaeche.
 */
private val DAEMON = ThreadFactory { r -> Thread(r, "ui-worker").apply { isDaemon = true } }

/** Pool, der bei `onDestroy` heruntergefahren wird (bereits laufende Auftraege duerfen enden). */
fun LifecycleOwner.lifecycleExecutor(threads: Int = 1): ExecutorService {
    val ex = if (threads <= 1) Executors.newSingleThreadExecutor(DAEMON) else Executors.newFixedThreadPool(threads, DAEMON)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            ex.shutdown()
            owner.lifecycle.removeObserver(this)
        }
    })
    return ex
}

/** Haupt-Thread-Handler, der nach `onDestroy` nichts mehr ausfuehrt und offene Nachrichten verwirft. */
fun LifecycleOwner.lifecycleHandler(): Handler {
    val handler = LifecycleHandler(lifecycle)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            handler.removeCallbacksAndMessages(null)
            owner.lifecycle.removeObserver(this)
        }
    })
    return handler
}

/** Verwirft jede neue Nachricht, sobald der Lebenszyklus beendet ist. */
internal class LifecycleHandler(private val lifecycle: Lifecycle) : Handler(Looper.getMainLooper()) {
    override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean =
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) false
        else super.sendMessageAtTime(msg, uptimeMillis)
}
