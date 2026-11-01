package de.minipc.appopsshizuku

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager

/**
 * Liest den Netzverkehr pro UID ueber den NetworkStatsManager. Braucht Nutzungszugriff
 * (PACKAGE_USAGE_STATS) - den erteilt sich die App per Shizuku selbst (siehe OnDemandSupport).
 * subscriberId = null: fuer WLAN immer, fuer Mobilfunk auf modernen Androids ausreichend und ohne
 * READ_PHONE_STATE.
 */
object NetStatsReader {

    data class Bytes(val foreground: Long, val background: Long)

    /** UID -> (Vordergrund, Hintergrund) Bytes im Zeitraum [since, now], WLAN + Mobilfunk summiert. */
    fun perUid(context: Context, since: Long): Map<Int, Bytes> {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val fg = HashMap<Int, Long>()
        val bg = HashMap<Int, Long>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val stats = runCatching { nsm.querySummary(type, null, since, now) }.getOrNull() ?: continue
            val bucket = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes <= 0) continue
                    val map = if (bucket.state == NetworkStats.Bucket.STATE_FOREGROUND) fg else bg
                    map[bucket.uid] = (map[bucket.uid] ?: 0L) + bytes
                }
            } finally {
                runCatching { stats.close() }
            }
        }
        return (fg.keys + bg.keys).associateWith { Bytes(fg[it] ?: 0L, bg[it] ?: 0L) }
    }
}
