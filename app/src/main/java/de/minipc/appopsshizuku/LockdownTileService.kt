package de.minipc.appopsshizuku

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import de.minipc.appopsshizuku.appops.LockdownStore

/**
 * Schnellstart-Kachel "Lockdown": ein Tipp entzieht allen Nutzer-Apps Kamera und Mikrofon
 * (Auswahl auf der Seite "Systemweit"), ein zweiter gibt sie genau diesen Apps zurueck.
 */
class LockdownTileService : TileService() {

    @Volatile private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        busy = true
        val activate = !LockdownStore(this).active
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            label = getString(R.string.lockdown_tile_busy)
            updateTile()
        }
        Thread {
            val r = if (activate) LockdownManager.activate(this) else LockdownManager.deactivate(this)
            busy = false
            android.os.Handler(mainLooper).post {
                render()
                val msg = when {
                    r.noShizuku -> getString(R.string.status_no_permission)
                    activate -> getString(R.string.lockdown_on_result, r.changed, r.failed)
                    else -> getString(R.string.lockdown_off_result, r.changed, r.failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = LockdownStore(this).active
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.lockdown_tile_label)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (active) R.string.lockdown_tile_on else R.string.lockdown_tile_off)
        }
        tile.updateTile()
    }
}
