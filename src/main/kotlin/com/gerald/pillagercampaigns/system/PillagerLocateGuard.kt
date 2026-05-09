package com.gerald.pillagercampaigns.system

object PillagerLocateGuard {
    fun blockedTarget(command: String, configuredStructureIds: Collection<String>): String? {
        val tokens = command.trim()
            .removePrefix("/")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.size < 3) return null
        val locateIndex = tokens.indices.firstOrNull { idx ->
            idx + 2 < tokens.size &&
                tokens[idx].equals("locate", ignoreCase = true) &&
                tokens[idx + 1].equals("structure", ignoreCase = true)
        } ?: return null
        val targetToken = tokens[locateIndex + 2]
        val target = targetToken.lowercase()
        val configured = configuredStructureIds.mapTo(mutableSetOf()) { it.lowercase() }
        if (target in configured) return targetToken
        if (target.startsWith("#") && target.contains("pillager")) return targetToken
        if (target.contains("pillager_outpost") || target.contains("pillager_camp")) return targetToken
        return null
    }
}
