package de.minipc.appopsshizuku.shizuku

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ShellBackend {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
    fun exec(vararg args: String): ShellResult

    /** Wie [exec], mit konfigurierbarem Timeout (Default: Standard-Timeout). */
    fun execTimeout(timeoutSeconds: Long, vararg args: String): ShellResult = exec(*args)

    /** Binaerdaten ueber den Prozess-Stdin schreiben (Default: ohne Stdin). */
    fun execWithStdin(stdin: ByteArray, vararg args: String): ShellResult = exec(*args)

    /** App per pm suspend einfrieren (Default: nicht unterstuetzt). */
    fun suspendPackage(packageName: String): ShellResult =
        ShellResult(-1, "", "suspend nicht unterstuetzt")

    /** Eingefrorene App per pm unsuspend wieder freigeben (Default: nicht unterstuetzt). */
    fun unsuspendPackage(packageName: String): ShellResult =
        ShellResult(-1, "", "unsuspend nicht unterstuetzt")
}
