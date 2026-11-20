package com.tvplayair.receiver.control

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidKeyMapperTest {

    @Test
    fun mapsTvDpadKeysToUsbHidUsages() {
        assertEquals(HidKeyMapper.KEY_UP_ARROW, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(HidKeyMapper.KEY_DOWN_ARROW, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(HidKeyMapper.KEY_LEFT_ARROW, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(HidKeyMapper.KEY_RIGHT_ARROW, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(HidKeyMapper.KEY_ENTER, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(HidKeyMapper.KEY_ESCAPE, HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_BACK))
        assertNull(HidKeyMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun buildsEightByteHidBootKeyboardReports() {
        val press = HidKeyMapper.buildKeyReport(HidKeyMapper.KEY_ENTER)
        assertArrayEquals(byteArrayOf(0, 0, 0x28, 0, 0, 0, 0, 0), press)

        val release = HidKeyMapper.buildReleaseReport()
        assertArrayEquals(ByteArray(8), release)
    }
}
