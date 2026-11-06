package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenOnPolicyTest {
    @Test
    fun `the screen is held on exactly while a set is ready, running or resting`() {
        val held = Stage.entries.filter { ScreenOnPolicy.keepsScreenOn(it) }.toSet()
        assertEquals(setOf(Stage.READY, Stage.IN_SET, Stage.RESTING), held)
    }
}
