package com.easycast.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {
    @Test
    fun aRotatedPinIsFourDigitsAndNeverTheOldOne() {
        repeat(2000) {
            val pin = PinPolicy.newPin("1234")
            assertEquals(4, pin.length)
            assertTrue(pin.toInt() in 1000..9999)
            assertNotEquals("1234", pin)
        }
    }

    @Test
    fun retriesWhenTheRandomValueEqualsTheOldPin() {
        val values = ArrayDeque(listOf(4821, 4821, 7305))
        val pin = PinPolicy.newPin("4821") { _, _ -> values.removeFirst() }
        assertEquals("7305", pin)
        assertTrue(values.isEmpty())
    }
}
