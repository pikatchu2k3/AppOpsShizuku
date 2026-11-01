package de.minipc.appopsshizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.minipc.appopsshizuku.appops.ProtectionStatus
import de.minipc.appopsshizuku.shizuku.ShizukuBackend

/**
 * Nach dem Neustart laeuft Shizuku meist nicht (es wird ueber Wireless Debugging von Hand gestartet).
 * Bis dahin ist jeder eingerichtete Schutz still inaktiv. Dieser Empfaenger zeigt dann eine
 * Benachrichtigung, damit das nicht unbemerkt bleibt. Erlaubt ist RECEIVE_BOOT_COMPLETED.
 *
 * Nichts wird hier veraendert - nur gelesen und gemeldet. Den Bildschirm-Waechter startet erst die
 * geoeffnete App (von Hand) bzw. er laeuft erst, wenn Shizuku verbunden ist.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        val app = context.applicationContext
        val protectedCount = runCatching { ProtectionStatus.protectedPackages(app).size }.getOrDefault(0)
        val shizukuUsable = runCatching {
            ShizukuBackend().let { it.isAvailable() && it.hasPermission() }
        }.getOrDefault(false)
        when (ProtectionStatus.bootHint(protectedCount, shizukuUsable)) {
            ProtectionStatus.HintKind.SHIZUKU_MISSING -> notify(app, protectedCount)
            null -> cancel(app)
        }
    }

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.boot_notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.boot_notif_channel_desc) }
        )
        return nm
    }

    private fun notify(context: Context, count: Int) {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.boot_notif_text, count)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.boot_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { channel(context).notify(NOTIF_ID, n) }
    }

    private fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "protection_status"
        private const val NOTIF_ID = 44

        /** Die geoeffnete App ruft das auf, sobald Shizuku verbunden ist: Hinweis wieder wegnehmen. */
        fun clear(context: Context) {
            runCatching {
                context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            }
        }
    }
}
