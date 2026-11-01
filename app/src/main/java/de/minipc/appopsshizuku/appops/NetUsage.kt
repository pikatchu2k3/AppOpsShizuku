package de.minipc.appopsshizuku.appops

/**
 * Netzverkehr pro App (reine Aufbereitung; die Zahlen liefert der NetworkStatsManager).
 * Trennt Vordergrund- und Hintergrundverkehr - Hintergrundverkehr einer selten geoeffneten App ist
 * das verdaechtige Signal. Alles bleibt auf dem Geraet.
 */
object NetUsage {

    data class Row(
        val packageName: String,
        val label: String,
        val foregroundBytes: Long,
        val backgroundBytes: Long,
        /** Tage seit der letzten Nutzung laut Zugriffsprotokoll; null = unbekannt. */
        val lastUsedDays: Int? = null,
        val offline: Boolean = false,
        val bgBlocked: Boolean = false
    ) {
        val total: Long get() = foregroundBytes + backgroundBytes
    }

    /** Menschliche Groesse (1024er-Schritte), z. B. "38,0 MB". Locale-neutral mit '.'. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format("%.1f %s", value, units[i])
    }

    /** Verdaechtig: nennenswerter Hintergrundverkehr, obwohl die App lange nicht offen war. */
    fun isSuspicious(row: Row, minBackgroundBytes: Long = 5L * 1024 * 1024, unusedDays: Int = 3): Boolean {
        val days = row.lastUsedDays ?: return false
        return row.backgroundBytes >= minBackgroundBytes && days >= unusedDays
    }

    /** Verdaechtige zuerst, dann nach Hintergrundverkehr absteigend, dann nach Gesamt. */
    fun sort(rows: List<Row>): List<Row> = rows.sortedWith(
        compareByDescending<Row> { isSuspicious(it) }
            .thenByDescending { it.backgroundBytes }
            .thenByDescending { it.total }
    )
}
