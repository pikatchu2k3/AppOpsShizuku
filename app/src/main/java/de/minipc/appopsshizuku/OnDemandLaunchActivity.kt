package de.minipc.appopsshizuku

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.LifecycleStore
import de.minipc.appopsshizuku.appops.OnDemandCallers
import de.minipc.appopsshizuku.appops.OnDemandExecutor
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Ziel der Launcher-Verknuepfung von "Nur bei Bedarf": App freigeben, starten, beim Dienst als
 * "geoeffnet" melden, fertig. Unsichtbar (durchsichtiges Theme), nur bei Problemen ein Dialog.
 *
 * Nicht exportiert: Angeheftete Verknuepfungen startet der Launcher im Namen UNSERER App, fremde
 * Apps koennen hierueber also keine gesperrte App freigeben. Zusaetzlich wird nur freigegeben,
 * was in den Einstellungen wirklich auf "Nur bei Bedarf" steht.
 *
 * Zweiter Eingang: der exportierte Alias [EXTERNAL_ALIAS] (Aktion [OnDemandCallers.ACTION_OPEN]) fuer
 * eigene Starter wie AppDialer NXT. Dort muss sich der Aufrufer per startActivityForResult zu
 * erkennen geben; eine neue Starter-App fragt einmal beim Nutzer nach ([OnDemandCallers.decide]).
 */
class OnDemandLaunchActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACKAGE = OnDemandCallers.EXTRA_PACKAGE
        /** Exportierter Alias (Manifest) fuer fremde Starter-Apps. */
        private const val EXTERNAL_ALIAS = "de.minipc.appopsshizuku.OnDemandExternalLaunch"
        private const val TAG = "AppOps.OnDemand"
        /** Nach einem Kaltstart liefert Shizuku seinen Binder asynchron - so lange warten wir hoechstens. */
        private const val BINDER_WAIT_MS = 3000L

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, OnDemandLaunchActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PACKAGE, packageName)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var binderListener: Shizuku.OnBinderReceivedListener? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DesignSettings.applyDynamicColorsIfEnabled(this)
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrBlank()) {
            finish()
            return
        }
        if (intent.component?.className == EXTERNAL_ALIAS) {
            checkCaller(pkg)
            return
        }
        proceed(pkg)
    }

    /** Fremder Starter: nur erkennbare und vom Nutzer erlaubte Apps kommen durch. */
    private fun checkCaller(pkg: String) {
        val caller = callingPackage
            ?: if (Build.VERSION.SDK_INT >= 34) launchedFromPackage else null
        val store = LifecycleStore(this)
        when (OnDemandCallers.decide(caller, packageName, pkg, store.onDemandCallers)) {
            OnDemandCallers.Decision.ALLOW -> proceed(pkg)
            OnDemandCallers.Decision.DENY -> {
                ErrorLog.w(TAG, "Fremder Start von $pkg abgelehnt (Aufrufer: $caller)")
                Toast.makeText(this, R.string.ondemand_caller_unknown, Toast.LENGTH_LONG).show()
                finish()
            }
            OnDemandCallers.Decision.ASK -> askCaller(caller!!, pkg, store)
        }
    }

    private fun askCaller(caller: String, pkg: String, store: LifecycleStore) {
        // Gesperrt hat nur, was wirklich auf "Nur bei Bedarf" steht - sonst gar nicht erst fragen
        if (store.get(pkg).onDemand != true) {
            proceed(pkg)
            return
        }
        var answered = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ondemand_caller_title)
            .setMessage(getString(R.string.ondemand_caller_text, labelOf(caller), labelOf(pkg)))
            .setPositiveButton(R.string.ondemand_caller_allow) { _, _ ->
                answered = true
                store.onDemandCallers = store.onDemandCallers + caller
                proceed(pkg)
            }
            .setNeutralButton(R.string.ondemand_caller_once) { _, _ ->
                answered = true
                proceed(pkg)
            }
            .setNegativeButton(R.string.ondemand_caller_deny, null)
            .setOnDismissListener { if (!answered) finish() }
            .show()
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun proceed(pkg: String) {
        if (LifecycleStore(this).get(pkg).onDemand != true) {
            Toast.makeText(this, R.string.ondemand_not_active, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (OnDemandSupport.launchIntentFor(this, pkg) == null &&
            runCatching { packageManager.getApplicationInfo(pkg, 0) }.isFailure
        ) {
            Toast.makeText(this, R.string.ondemand_not_installed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        whenShizukuReady { ready -> if (ready) openApp(pkg) else showNoShizuku() }
    }

    override fun onDestroy() {
        binderListener?.let { Shizuku.removeBinderReceivedListener(it) }
        executor.shutdown()
        super.onDestroy()
    }

    private fun shizukuUsable(): Boolean {
        val b = ShizukuBackend()
        return runCatching { b.isAvailable() && b.hasPermission() }.getOrDefault(false)
    }

    /** Sofort, wenn Shizuku schon verbunden ist; sonst kurz auf den Binder warten. */
    private fun whenShizukuReady(block: (Boolean) -> Unit) {
        if (shizukuUsable()) {
            block(true)
            return
        }
        var done = false
        fun finishWait(ok: Boolean) {
            if (done) return
            done = true
            block(ok)
        }
        val listener = Shizuku.OnBinderReceivedListener { handler.post { finishWait(shizukuUsable()) } }
        binderListener = listener
        Shizuku.addBinderReceivedListener(listener)
        handler.postDelayed({ finishWait(shizukuUsable()) }, BINDER_WAIT_MS)
    }

    private fun openApp(pkg: String) {
        if (started) return
        started = true
        val app = applicationContext
        executor.execute {
            val result = OnDemandExecutor(ShizukuBackend()).unlock(pkg)
            Log.i(TAG, "$pkg freigegeben: ok=${result.ok} ${result.state} ${result.raw}")
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (!result.ok) {
                    showError(getString(R.string.ondemand_unlock_failed, result.state.toString()))
                    return@post
                }
                LifecycleStore(app).markOpened(pkg, System.currentTimeMillis())
                // Dienst (laeuft fuer Bedarfs-Apps ohnehin) bekommt den Neuzugang ueber onStartCommand mit
                LifecycleScreenService.sync(app)
                val launch = OnDemandSupport.launchIntentFor(app, pkg)
                if (launch == null) {
                    showError(getString(R.string.ondemand_no_launcher))
                    return@post
                }
                runCatching { startActivity(launch) }
                    .onFailure { showError(it.message ?: it.javaClass.simpleName); return@post }
                finish()
            }
        }
    }

    private fun showNoShizuku() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ondemand_no_shizuku_title)
            .setMessage(R.string.ondemand_no_shizuku_text)
            .setPositiveButton(R.string.ondemand_open_shizuku) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage("moe.shizuku.privileged.api")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    Toast.makeText(this, R.string.error_shizuku_app_missing, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun showError(text: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ondemand_error_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
