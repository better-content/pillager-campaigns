package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PillagerLocateGuardTest {
    private val configured = listOf(
        "minecraft:pillager_outpost",
        "takesapillage:bastille",
        "takesapillage:pillager_camp",
        "towns_and_towers:exclusives/pillager_outpost_classic",
    )

    @Test
    fun `blocks configured pillager base structure locate commands`() {
        assertEquals(
            "minecraft:pillager_outpost",
            PillagerLocateGuard.blockedTarget("locate structure minecraft:pillager_outpost", configured),
        )
        assertEquals(
            "takesapillage:bastille",
            PillagerLocateGuard.blockedTarget("/locate structure takesapillage:bastille", configured),
        )
        assertEquals(
            "towns_and_towers:exclusives/pillager_outpost_classic",
            PillagerLocateGuard.blockedTarget("locate structure towns_and_towers:exclusives/pillager_outpost_classic", configured),
        )
    }

    @Test
    fun `blocks pillager structure tags without blocking unrelated locate commands`() {
        assertEquals(
            "#minecraft:pillager_outpost",
            PillagerLocateGuard.blockedTarget("locate structure #minecraft:pillager_outpost", configured),
        )
        assertNull(PillagerLocateGuard.blockedTarget("locate structure minecraft:village_plains", configured))
        assertNull(PillagerLocateGuard.blockedTarget("locate biome minecraft:plains", configured))
        assertNull(PillagerLocateGuard.blockedTarget("sam settlements list", configured))
    }

    @Test
    fun `guard handles case and spacing variants to prevent bypass`() {
        assertEquals(
            "MINECRAFT:PILLAGER_OUTPOST",
            PillagerLocateGuard.blockedTarget("/LOCATE   STRUCTURE   MINECRAFT:PILLAGER_OUTPOST", configured),
        )
        assertEquals(
            "takesapillage:pillager_camp",
            PillagerLocateGuard.blockedTarget("locate structure takesapillage:pillager_camp", configured),
        )
        assertEquals(
            "minecraft:pillager_outpost",
            PillagerLocateGuard.blockedTarget("execute as @p run locate structure minecraft:pillager_outpost", configured),
        )
    }
}
