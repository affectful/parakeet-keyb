package com.parakeet.keys

internal enum class LatchState {
    OFF,
    ARMED,
    LOCKED,
}

internal class ModifierLatch {
    private val states = mutableMapOf<String, LatchState>()

    fun state(name: String): LatchState = states[name] ?: LatchState.OFF

    fun tap(name: String): LatchState {
        val next = if (state(name) == LatchState.OFF) LatchState.ARMED else LatchState.OFF
        states[name] = next
        return next
    }

    fun lock(name: String): LatchState {
        val next = if (state(name) == LatchState.LOCKED) LatchState.OFF else LatchState.LOCKED
        states[name] = next
        return next
    }

    fun active(name: String): Boolean = state(name) != LatchState.OFF

    fun consumeArmed() {
        states.entries.forEach { entry ->
            if (entry.value == LatchState.ARMED) entry.setValue(LatchState.OFF)
        }
    }

    fun clear() = states.clear()
}
