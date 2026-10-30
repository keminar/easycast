package com.easycast.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {
    @Test
    fun dueWhenTheDayChangedAndNothingPlays() {
        assertTrue(PinPolicy.rotationDue("20261030", "20261031", playing = false))
        // Left running over several days: still due as soon as it is checked.
        assertTrue(PinPolicy.rotationDue("20261001", "20261031", playing = false))
    }

    @Test
    fun notDueOnTheSameDay() {
        assertFalse(PinPolicy.rotationDue("20261031", "20261031", playing = false))
    }

    @Test
    fun waitsWhileSomethingPlaysThenRotatesOnceItStops() {
        assertFalse(PinPolicy.rotationDue("20261030", "20261031", playing = true))
        assertTrue(PinPolicy.rotationDue("20261030", "20261031", playing = false))
    }

    @Test
    fun anUnknownCreationDayCountsAsDue() {
        // Installs from before daily rotation have no recorded day.
        assertTrue(PinPolicy.rotationDue(null, "20261031", playing = false))
    }

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

    @Test
    fun theDayChangesAtMidnightAcrossAMonthEnd() {
        // yyyyMMdd strings compare by equality only, so the month/year rollover needs no special case.
        assertTrue(PinPolicy.rotationDue("20261031", "20261101", playing = false))
        assertTrue(PinPolicy.rotationDue("20261231", "20270101", playing = false))
    }
}
