// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Profile
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * A quick settings tile for one profile: it is named as the profile is, lit
 * while the profile is in use, and a tap changes to it. Android wants a class
 * a tile, named in the manifest, so there are as many as there can be
 * profiles, each for the profile with its [slot]; [io.github.beesan.lfg.Profiles.syncTiles]
 * shows Android only those that have one.
 */
abstract class ProfileTile(private val slot: Int) : TileService() {
    private val profiles get() = (application as App).profiles

    private fun profile(): Profile? = profiles.list.value.firstOrNull { it.slot == slot }

    /** While the quick settings are open: the tile draws itself again whenever the profiles change, as when another's tile is tapped. */
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = MainScope().launch { profiles.list.collect { show() } }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    private fun show() {
        val profile = profile()
        qsTile?.apply {
            if (profile == null) {
                state = Tile.STATE_UNAVAILABLE
            } else {
                label = profile.shown
                // Under a name of the player's own, the language; under the language, whose tile this is.
                subtitle = if (profile.shown.startsWith(profile.language.title)) "LFG" else profile.language.title
                state = if (profile.inUse) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Every tile showing hears of the change and draws itself again, this one too.
        profile()?.let { profiles.use(it.id) }
    }

    companion object {
        /** The tile of the profile with [slot], from 1. */
        fun of(slot: Int): Class<out ProfileTile> = when (slot) {
            1 -> ProfileTile1::class.java
            2 -> ProfileTile2::class.java
            3 -> ProfileTile3::class.java
            4 -> ProfileTile4::class.java
            5 -> ProfileTile5::class.java
            else -> ProfileTile6::class.java
        }
    }
}

class ProfileTile1 : ProfileTile(1)
class ProfileTile2 : ProfileTile(2)
class ProfileTile3 : ProfileTile(3)
class ProfileTile4 : ProfileTile(4)
class ProfileTile5 : ProfileTile(5)
class ProfileTile6 : ProfileTile(6)
