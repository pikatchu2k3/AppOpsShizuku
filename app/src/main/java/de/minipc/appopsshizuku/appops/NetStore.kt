package de.minipc.appopsshizuku.appops

import android.content.Context

/** Schluessel = Paketname, Wert = [NetMarks]. Nur Markierung "von uns geaendert"; Systemzustand bleibt Wahrheit. */
class NetStore(context: Context) {

    private val prefs = context.getSharedPreferences("net_changes", Context.MODE_PRIVATE)

    fun get(pkg: String): Set<NetSwitch> = NetMarks.decode(prefs.getString(pkg, null))

    fun mark(pkg: String, sw: NetSwitch, ours: Boolean) {
        val next = if (ours) get(pkg) + sw else get(pkg) - sw
        prefs.edit().apply { if (next.isEmpty()) remove(pkg) else putString(pkg, NetMarks.encode(next)) }.apply()
    }

    /** Haben WIR die Firewall-Kette eingeschaltet (muss beim Zuruecksetzen wieder weg)? */
    fun chainMarked(): Boolean = prefs.getBoolean(CHAIN_KEY, false)

    fun setChainMarked(ours: Boolean) {
        prefs.edit().putBoolean(CHAIN_KEY, ours).apply()
    }

    /** Pakete, bei denen WIR den Offline-Schalter gesetzt haben (leer = Kette darf wieder aus). */
    fun packagesWithOffline(): List<String> =
        prefs.all.filterKeys { !it.startsWith(MARK_PREFIX) }
            .filterValues { it is String && NetMarks.decode(it).contains(NetSwitch.OFFLINE) }
            .keys.toList()

    companion object {
        /** Praefix fuer eigene Schluessel - Paketnamen koennen damit nicht kollidieren. */
        private const val MARK_PREFIX = "__"
        private const val CHAIN_KEY = "__chain3"
    }
}
