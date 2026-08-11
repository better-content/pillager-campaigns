package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaidAiDslTest {
    @Test fun `policy retains native attacks and returns after ten minutes`() {
        val policy = raidAi {
            assignedTarget()
            territoryBoundary()
            nativeAttacks()
            actualWeaponRange(17.0)
            cohesion(20.0)
            formulaicSuccessor()
            returnAfterIdle(12_000L)
        }
        assertTrue(policy.targetPlayer && policy.territoryBound && policy.preserveNativeAttacks && policy.successorByScore)
        assertEquals(17.0, policy.weaponRange)
        assertEquals(12_000L, policy.idleReturnTicks)
    }
}
