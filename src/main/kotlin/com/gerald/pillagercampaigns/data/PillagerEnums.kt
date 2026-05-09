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

enum class BaseState {
    PLANNED,
    MATERIALIZED,
    DEFEATED,
}

enum class BaseForm {
    UNKNOWN,
    JIGSAW_OUTPOST,
}

enum class BaseMaterializationFailure {
    NONE,
    IN_PROGRESS,
    NO_SITE,
    FOOTPRINT_NOT_LOADED,
    POOL_MISSING,
    JIGSAW_FAILED,
}
