package com.parakeet.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierLatchTest {
    @Test
    fun tapArmsAndNextKeyConsumes() {
        val latch = ModifierLatch()

        assertEquals(LatchState.ARMED, latch.tap("Ctrl"))
        assertTrue(latch.active("Ctrl"))

        latch.consumeArmed()

        assertFalse(latch.active("Ctrl"))
    }

    @Test
    fun longPressLocksUntilToggled() {
        val latch = ModifierLatch()

        assertEquals(LatchState.LOCKED, latch.lock("Shift"))
        latch.consumeArmed()
        assertTrue(latch.active("Shift"))

        assertEquals(LatchState.OFF, latch.tap("Shift"))
    }

    @Test
    fun consumingArmedKeepsLockedModifiers() {
        val latch = ModifierLatch()
        latch.tap("Alt")
        latch.lock("Ctrl")

        latch.consumeArmed()

        assertFalse(latch.active("Alt"))
        assertTrue(latch.active("Ctrl"))
    }
}
