package com.bettercontent.pillagercampaigns.data

enum class OfficerRole {
    CAPTAIN,
    WARLORD,
}

enum class OfficerState {
    IDLE,
    ASSIGNED,
    DEPLOYED,
    RECOVERING,
    DEAD,
}

enum class OfficerRank {
    SCOUT,
    CAPTAIN,
    DREAD_CAPTAIN,
}

enum class CampaignState {
    TRAVELING,
    READY_TO_MATERIALIZE,
    MATERIALIZING,
    ACTIVE,
    PAUSED,
    RETURNING,
    RESOLVED,
}

enum class PresenceType {
    INVASION_SQUAD,
    WARLORD,
}

enum class RallyPresenceState {
    DORMANT,
    MATERIALIZED,
    LOST,
}

enum class PresenceMaterializationResult {
    NOT_LOADED,
    NO_SAFE_SITE,
    LIVE_ALREADY_PRESENT,
    COOLDOWN,
    SUCCESS,
}

enum class NemesisEventType {
    KILLED_PLAYER,
    DEFEATED_PLAYER_CAMPAIGN,
    LOST_CAMPAIGN,
    SURVIVED_RETREAT,
    WAS_DEFEATED_BY_PLAYER,
    LED_SUCCESSFUL_ASSAULT,
    FAILED_MATERIALIZATION,
    PROMOTED,
    DEMOTED,
    WARBAND_COLLAPSED,
}

enum class CampaignOutcome {
    CAPTAIN_VICTORY,
    CAPTAIN_SURVIVED_DEFEAT,
    CAPTAIN_KILLED,
    WARBAND_COLLAPSE,
    ABORTED,
}
