package com.bettercontent.pillagercampaigns.sam.pillagers

import kotlin.test.Test
import kotlin.test.assertEquals

class SamPillagersModuleTest {
    @Test
    fun `module exposes invasion movement only`() {
        assertEquals("sam:pillagers", SamPillagersModule.id.toString())
        assertEquals(listOf(PillagerInvasionMovement), SamPillagersModule.movementTypes())
        assertEquals("sampillagers:invasion", PillagerInvasionMovement.id.toString())
    }
}
