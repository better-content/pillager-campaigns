package com.gerald.pillagercampaigns.data

enum class OfficerState {
    AVAILABLE,
    DEPLOYED,
    DEAD,
}

enum class OfficerRank {
    SCOUT,
    CAPTAIN,
    WARLORD,
}

enum class OfficerClass {
    PILLAGER,
    VINDICATOR,
    WITCH,
    EVOKER,
    ILLUSIONER,
}

enum class CampaignState {
    TRAVELING,
    READY_TO_MATERIALIZE,
    MATERIALIZING,
    ACTIVE,
    RESOLVED,
}
