package de.minipc.appopsshizuku.shizuku

import android.app.Activity
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku-Lifecycle: Verbindungsstatus + Berechtigung ueberwachen.
 * Ergebnis des Permission-Requests kommt in der Activity via
 * onRequestPermissionsResult(requestCode == REQUEST_CODE).
 */
class ShizukuManager(private val onStateChanged: () -> Unit) {

    companion object {
        const val API_PERMISSION = "moe.shizuku.manager.permission.API_V23"
        const val REQUEST_CODE = 1001
    }

    var isConnected: Boolean = false
        private set
    var hasPermission: Boolean = false
        private set

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) refresh()
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun refresh() {
        isConnected = try { Shizuku.pingBinder() } catch (e: Exception) { false }
        hasPermission = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        onStateChanged()
    }

    fun removeListeners() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun requestPermission(activity: Activity) {
        if (isConnected && !hasPermission) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }
}
