package net.typeblog.shelter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStateTest {
    @Test
    fun probesWhenSetupIsIncomplete() {
        assertTrue(MainActivity.shouldProbeWorkProfile(isSettingUp = true, hasSetup = false))
        assertTrue(MainActivity.shouldProbeWorkProfile(isSettingUp = false, hasSetup = false))
        assertFalse(MainActivity.shouldProbeWorkProfile(isSettingUp = false, hasSetup = true))
    }
}
