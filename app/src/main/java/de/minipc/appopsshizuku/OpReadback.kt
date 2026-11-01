package de.minipc.appopsshizuku

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.shizuku.ShellBackend

/**
 * Ist-Zustand einer Op, zuverlaessig und ohne Shell wo moeglich:
 * - Berechtigungs-Ops: aus den Grant-Flags des PackageManagers ("allow"/"deny"; [NOT_REQUESTED], wenn
 *   die App die Berechtigung gar nicht anfordert - dann gibt es nichts zu pruefen oder zu setzen).
 * - Reine AppOps: `cmd appops get` ueber den [AppOpsController].
 */
object OpReadback {
    const val NOT_REQUESTED = "not-requested"

    fun read(context: Context, backend: ShellBackend, pkg: String, op: AppOp): String? {
        val perm = op.permissionName ?: return AppOpsController(backend).getMode(pkg, op)
        val pi = runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null
        val idx = pi.requestedPermissions?.indexOf(perm) ?: -1
        if (idx < 0) return NOT_REQUESTED
        val flags = pi.requestedPermissionsFlags?.getOrNull(idx) ?: return null
        return if ((flags and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) "allow" else "deny"
    }
}
