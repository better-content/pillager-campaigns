package com.bettercontent.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvasionRuntimeTargetPolicyTest {
    @Test fun `live player in the mob level remains a valid pursuit target`() {
        assertTrue(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.LIVE_SAME_LEVEL))
    }

    @Test fun `dead target is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.DEAD))
    }

    @Test fun `absent logged out target is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.ABSENT))
    }

    @Test fun `live target in another dimension is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.DIFFERENT_LEVEL))
    }
}
