package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.engine.CapabilityVector
import com.gerald.pillagercampaigns.engine.ChunkPosition
import com.gerald.pillagercampaigns.engine.EngineCatalog
import com.gerald.pillagercampaigns.engine.EngineFrame
import com.gerald.pillagercampaigns.engine.EngineState
import com.gerald.pillagercampaigns.engine.OfficerState
import com.gerald.pillagercampaigns.engine.RecruitDefinition
import com.gerald.pillagercampaigns.engine.WarbandEngine
import com.gerald.pillagercampaigns.engine.WarbandRules
import com.gerald.pillagercampaigns.engine.WarbandState
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Mob
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Minecraft adapter for the authoritative pure engine. Registry inspection and
 * ItemStack construction stay here; economy and selection transitions do not.
 */
object PillagerEngineBridge {
    internal data class LiveRecruit(val mob: Mob, val definition: RecruitDefinition)

    private fun rules() = WarbandRules(
        minimumAggression = PillagerCampaignsConfig.minimumAggression.get(),
        maximumAggression = PillagerCampaignsConfig.maximumAggression.get(),
        idleReturnTicks = PillagerCampaignsConfig.idleReturnTicks.get().toLong(),
        materializeDistanceChunks = PillagerCampaignsConfig.materializeDistanceChunks.get(),
        warbandLearningRate = PillagerCampaignsConfig.warbandLearningRate.get(),
        captainLearningRate = PillagerCampaignsConfig.captainLearningRate.get(),
        threatLearningRate = PillagerCampaignsConfig.threatLearningRate.get(),
    )

    fun advanceEconomies(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        data.warbands.values.asSequence().filter { !it.defeated }.forEach { warband ->
            val elapsed = (now - warband.lastEconomyTick).coerceAtLeast(0L)
            if (elapsed == 0L) return@forEach
            warband.lastEconomyTick = now
            val candidates = TinkersArmoryOptimizer.liveEquipmentCandidates(warband, server)
            val activeThreat = data.campaigns.values.asSequence()
                .filter { it.originWarbandId == warband.id && it.state != CampaignState.RESOLVED }
                .sumOf { it.committedThreat.toDouble() }
            val core = coreWarband(warband).copy(
                capacity = (warband.capacity - warband.garrisonThreat.values.sum() - activeThreat).coerceAtLeast(1.0),
            )
            val state = EngineState(
                tick = now - elapsed,
                sequence = (warband.id.mostSignificantBits xor warband.id.leastSignificantBits xor now).and(Long.MAX_VALUE),
                warbands = linkedMapOf(core.id to core),
            )
            val catalog = EngineCatalog(
                revision = "forge-live",
                recruits = emptyList(),
                materials = TinkersArmoryOptimizer.materialDefinitions(warband),
                equipment = candidates.map { it.definition },
            )
            val result = WarbandEngine.transition(state, EngineFrame(elapsed), catalog, rules())
            warband.reserve = core.reserveThreat.roundToInt().coerceAtLeast(0)
            warband.raidPool = core.raidPool.coerceAtLeast(0.0)
            warband.recruitTickDebt = core.recruitTickDebt
            warband.mobilizationTickDebt = core.mobilizationTickDebt
            warband.extractionTickDebt = core.extractionTickDebt
            warband.materialLedger.clear()
            warband.materialLedger.putAll(core.materialLedger)
            result.events.asSequence().filter { it.type == "manufactured" }.forEach { event ->
                if (warband.armory.size >= warband.capacity) return@forEach
                candidates.firstOrNull { it.definition.id == event.detail }?.let { candidate ->
                    TinkersArmoryOptimizer.realize(warband, candidate, consume = false)?.let { stack ->
                        warband.armory += stack.save(net.minecraft.nbt.CompoundTag())
                    }
                }
            }
        }
    }

    internal fun chooseRecruit(
        warband: PillagerWarband,
        officerPreferences: Map<String, Double>,
        budget: Double,
        options: List<LiveRecruit>,
        sequence: Long,
    ): LiveRecruit? {
        if (options.isEmpty()) return null
        val core = coreWarband(warband)
        val officer = OfficerState("live-officer", core.factionId, core.id, officerPreferences.toMutableMap())
        val state = EngineState(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        val selected = WarbandEngine.chooseRecruit(
            state, core, officer, EngineCatalog("forge-live", options.map { it.definition }), budget, rules = rules(),
        ) ?: return null
        return options.firstOrNull { it.definition.id == selected.id }
    }

    fun raidBudget(warband: PillagerWarband, minimumThreat: Double): Double = rules().raidBudget(coreWarband(warband), minimumThreat)

    fun coreWarband(warband: PillagerWarband): WarbandState = WarbandState(
        id = warband.id.toString(),
        factionId = warband.factionId.toString(),
        rally = ChunkPosition(warband.dimension.toString(), warband.rallyChunkX, warband.rallyChunkZ),
        capacity = warband.capacity.toDouble(),
        reserveThreat = warband.reserve.toDouble(),
        raidPool = warband.raidPool,
        garrisonThreat = warband.garrisonThreat.values.sum(),
        aggression = warband.aggression,
        environment = com.gerald.pillagercampaigns.engine.EnvironmentTraits(
            warband.environment.habitability,
            warband.environment.biomass,
            warband.environment.mineralPotential,
            warband.environment.exoticPotential,
            warband.environment.travelFriction,
        ),
        preferences = warband.preferences.toMutableMap(),
        materialLedger = warband.materialLedger.toMutableMap(),
        empiricalThreat = warband.empiricalThreat.toMutableMap(),
        recruitTickDebt = warband.recruitTickDebt,
        mobilizationTickDebt = warband.mobilizationTickDebt,
        extractionTickDebt = warband.extractionTickDebt,
        nextRaidTick = warband.nextRaidTick,
        defeated = warband.defeated,
        activeCampaignLimit = warband.activeCampaignLimit,
    )

    fun recruitDefinition(id: String, threat: Double, mob: Mob): RecruitDefinition = RecruitDefinition(
        id,
        threat,
        CapabilityVector(
            durability = (mob.maxHealth + mob.armorValue * 2.0) / 40.0,
            damage = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) / 8.0,
            mobility = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) / 0.3,
            range = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE) / 32.0,
        ),
        supportedEquipmentActions = buildSet {
            if (mob is net.minecraft.world.entity.monster.CrossbowAttackMob || mob is net.minecraft.world.entity.monster.RangedAttackMob) add("ranged")
            if (mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) > 0.0) add("melee")
        },
    )
}
