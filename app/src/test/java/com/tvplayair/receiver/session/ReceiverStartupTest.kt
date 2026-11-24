package com.tvplayair.receiver.session

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverStartupTest {
    @Test
    fun disablingAutomaticStartAppliesToBootAndPackageUpdates() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            assertTrue(BootReceiver.shouldStartReceiver(action, enabled = true))
            assertFalse(BootReceiver.shouldStartReceiver(action, enabled = false))
        }
        assertFalse(BootReceiver.shouldStartReceiver(null, enabled = true))
    }
}
