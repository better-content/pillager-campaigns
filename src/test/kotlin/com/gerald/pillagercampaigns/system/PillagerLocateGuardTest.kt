package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertNull

class PillagerLocateGuardTest {
    private val configured = listOf(
        "minecraft:pillager_outpost",
        "takesapillage:bastille",
        "takesapillage:pillager_camp",
        "towns_and_towers:exclusives/pillager_outpost_classic",
    )

    @Test
    fun `allows configured pillager structure locate commands`() {
        assertNull(PillagerLocateGuard.blockedTarget("locate structure minecraft:pillager_outpost", configured))
        assertNull(PillagerLocateGuard.blockedTarget("/locate structure takesapillage:bastille", configured))
        assertNull(PillagerLocateGuard.blockedTarget("locate structure towns_and_towers:exclusives/pillager_outpost_classic", configured))
    }

    @Test
    fun `allows pillager structure tags and unrelated locate commands`() {
        assertNull(PillagerLocateGuard.blockedTarget("locate structure #minecraft:pillager_outpost", configured))
        assertNull(PillagerLocateGuard.blockedTarget("locate structure minecraft:village_plains", configured))
        assertNull(PillagerLocateGuard.blockedTarget("locate biome minecraft:plains", configured))
        assertNull(PillagerLocateGuard.blockedTarget("sam settlements list", configured))
    }

    @Test
    fun `case and spacing variants are not blocked`() {
        assertNull(PillagerLocateGuard.blockedTarget("/LOCATE   STRUCTURE   MINECRAFT:PILLAGER_OUTPOST", configured))
        assertNull(PillagerLocateGuard.blockedTarget("locate structure takesapillage:pillager_camp", configured))
        assertNull(PillagerLocateGuard.blockedTarget("execute as @p run locate structure minecraft:pillager_outpost", configured))
    }
}
