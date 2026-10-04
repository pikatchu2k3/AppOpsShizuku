package de.minipc.appopsshizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * getPackageInfo mit Flags - ab API 33 ueber PackageInfoFlags (die Int-Variante ist veraltet).
 * Wirft [PackageManager.NameNotFoundException] wie das Original.
 */
fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, flags)
    }
