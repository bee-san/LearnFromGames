// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.beesan.lfg.ui.MainActivity

/**
 * Quick settings tiles that ask for a reading over any app. Games are read by
 * themselves; an ordinary app is left alone until one of these, the LFG
 * button or the lookup shortcut asks.
 */
abstract class ReadTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = when {
                LfgAccessibilityService.instance == null -> Tile.STATE_UNAVAILABLE
                active() -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    protected open fun active() = false

    override fun onClick() {
        super.onClick()
        val service = LfgAccessibilityService.instance
        if (service == null) {
            // Screen reading is off: the app's first screen says how to turn it on.
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                // The only form Android 13 has.
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }
        act(service)
    }

    protected abstract fun act(service: LfgAccessibilityService)
}

/** Reads the screen once: the next tap on a word looks it up. */
class ReadScreenTile : ReadTile() {
    override fun act(service: LfgAccessibilityService) = service.ask(collapse = true)
}

/** Drag a box to read; or, while a region is being read on every change, stop. */
class ReadRegionTile : ReadTile() {
    override fun active() = LfgAccessibilityService.asking.value

    override fun act(service: LfgAccessibilityService) {
        if (LfgAccessibilityService.asking.value) {
            service.endAsked()
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        } else {
            service.pickRegion(collapse = true)
        }
    }
}
