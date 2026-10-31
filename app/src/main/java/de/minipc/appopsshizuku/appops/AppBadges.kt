package de.minipc.appopsshizuku.appops

/**
 * Status-Symbole der App-Liste: was DIESE App an einer fremden App veraendert hat.
 * Quelle sind nur die eigenen Speicher (Profil, Netz-Markierungen, Lebenszyklus, Quarantaene) -
 * kein Shell-Aufruf, deshalb auch ohne Shizuku sofort sichtbar. Der Geraetezustand selbst wird
 * auf der Detailseite zurueckgelesen.
 */
enum class AppBadge { BLOCKED, OFFLINE, BG_NET, ON_DEMAND, SCREEN_OFF, WHILE_IN_USE, QUARANTINE }

data class BadgeSet(val badges: List<AppBadge>, val blockedCount: Int) {
    val isEmpty: Boolean get() = badges.isEmpty()
}

object AppBadges {

    fun compute(
        profile: Map<String, String>,
        net: Set<NetSwitch>,
        lifecycle: LifecycleEntry?,
        whileInUse: Boolean = false,
        quarantined: Boolean = false
    ): BadgeSet {
        val blocked = profile.values.count { it == "deny" || it == "ignore" }
        val out = mutableListOf<AppBadge>()
        if (quarantined) out += AppBadge.QUARANTINE
        if (blocked > 0) out += AppBadge.BLOCKED
        if (NetSwitch.OFFLINE in net) out += AppBadge.OFFLINE
        else if (NetSwitch.BG_DATA in net || NetSwitch.BG_RESTRICT in net) out += AppBadge.BG_NET
        if (lifecycle?.onDemand == true) out += AppBadge.ON_DEMAND
        else if (lifecycle?.screenOffExit == true) out += AppBadge.SCREEN_OFF
        if (whileInUse) out += AppBadge.WHILE_IN_USE
        return BadgeSet(out, blocked)
    }
}
