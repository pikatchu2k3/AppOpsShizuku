package de.minipc.appopsshizuku.appops

/**
 * Arbeitsprofil (managed profile): In ein zweites Profil verschobene Apps sehen weder die Kontakte
 * noch die Dateien des Hauptprofils - oft robuster als einzelne Sperren. Reine Parser (JVM-testbar);
 * die Shell-Aktionen macht der Manager.
 *
 * Ein Arbeitsprofil anzulegen braucht einen Profilbesitzer (Device Policy Controller). Apps in ein
 * BEREITS vorhandenes Profil (z. B. von Shelter) zu verschieben geht dagegen allein mit Shell-Rechten.
 */
object WorkProfile {

    /** FLAG_MANAGED_PROFILE aus android.content.pm.UserInfo. */
    private const val FLAG_MANAGED_PROFILE = 0x00000020

    data class User(val id: Int, val name: String, val flagsHex: String, val running: Boolean) {
        val managed: Boolean get() = (flagsHex.toLongOrNull(16) ?: 0L) and FLAG_MANAGED_PROFILE.toLong() != 0L
    }

    /** `pm list users` -> Liste. Zeilen: `UserInfo{10:Work profile:30} running`. */
    fun parseUsers(out: String): List<User> {
        val re = Regex("""UserInfo\{(\d+):([^:}]*):([0-9a-fA-F]+)\}([^\n]*)""")
        return re.findAll(out).map { m ->
            User(
                id = m.groupValues[1].toInt(),
                name = m.groupValues[2],
                flagsHex = m.groupValues[3],
                running = m.groupValues[4].contains("running")
            )
        }.toList()
    }

    /** Erstes Arbeitsprofil oder null. */
    fun managedProfile(users: List<User>): User? = users.firstOrNull { it.managed }

    /** `pm create-user ...` -> neue User-ID aus "Success: created user id 10". */
    fun parseCreatedUserId(out: String): Int? =
        Regex("""created user id (\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()

    /** `pm list packages --user N` -> Paketnamen. */
    fun parsePackages(out: String): Set<String> =
        Regex("""^package:([^\s]+)""", RegexOption.MULTILINE).findAll(out).map { it.groupValues[1] }.toSet()

    /**
     * `dumpsys device_policy` -> Paketname des Profilbesitzers des Users [userId], oder null.
     * Zeilenform (AOSP, variiert): `Profile Owner (User 10): ... admin=ComponentInfo{pkg/cls}`.
     */
    fun parseProfileOwner(dump: String, userId: Int): String? {
        val block = Regex("""Profile Owner \(User $userId\):.*?admin=ComponentInfo\{([^/]+)/""", RegexOption.DOT_MATCHES_ALL)
            .find(dump) ?: return null
        return block.groupValues[1]
    }
}
