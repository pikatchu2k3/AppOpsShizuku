package de.minipc.appopsshizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Weckruf fuer die Automatik-Regeln (AlarmManager) und erneutes Planen nach dem Neustart.
 * Die eigentliche Auswertung macht [AutoRuleManager].
 */
class AutoRuleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutoRuleManager.ACTION_EVAL,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                Thread {
                    try {
                        AutoRuleManager.evaluate(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
