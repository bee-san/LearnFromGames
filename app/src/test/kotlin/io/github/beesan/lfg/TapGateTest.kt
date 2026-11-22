// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.TapGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGateTest {
    @Test
    fun `with a controller a tap is the game's until the shortcut`() {
        assertFalse(TapGate.open(TapGate.AUTO, hasController = true, lookupMode = false, asked = false))
        assertTrue(TapGate.open(TapGate.AUTO, hasController = true, lookupMode = true, asked = false))
    }

    @Test
    fun `with no controller a tap always looks up`() {
        assertTrue(TapGate.open(TapGate.AUTO, hasController = false, lookupMode = false, asked = false))
    }

    @Test
    fun `the setting overrides what the device has`() {
        assertTrue(TapGate.open(TapGate.ALWAYS, hasController = true, lookupMode = false, asked = false))
        assertFalse(TapGate.open(TapGate.SHORTCUT, hasController = false, lookupMode = false, asked = false))
        assertTrue(TapGate.open(TapGate.SHORTCUT, hasController = false, lookupMode = true, asked = false))
    }

    @Test
    fun `a reading asked for takes taps whatever the setting`() {
        assertTrue(TapGate.open(TapGate.SHORTCUT, hasController = true, lookupMode = false, asked = true))
    }
}
