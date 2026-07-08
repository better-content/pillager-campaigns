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

enum class WarbandArchetype {
    SKIRMISHER,
    BLACKGUARD,
    HEX,
    SABOTEUR,
}

enum class WarbandRole {
    WARLORD,
    CAPTAIN,
    LINE,
    SPECIALIST,
}

enum class CampaignState {
    TRAVELING,
    READY_TO_MATERIALIZE,
    MATERIALIZING,
    ACTIVE,
    PAUSED,
    RESOLVED,
}

enum class PresenceType {
    INVASION_SQUAD,
    WARLORD,
}

enum class PresenceMaterializationResult {
    NOT_LOADED,
    NO_SAFE_SITE,
    LIVE_ALREADY_PRESENT,
    COOLDOWN,
    SUCCESS,
}
