# Pillager Campaigns Programmatic Balance Notes

Catalog: `forge-live-sha256:9474f19e7196cafe976d2bef4bc59212fc1f89147f4df9430bba1386ca0224ce`

The report covers 342 deterministic cells using the exact authoritative Warband Core and a Forge-captured live catalog.

## Longitudinal overview

| Horizon | Cells | Raids/day | Distinct recruits | Dominant share | Equipment coverage |
|---:|---:|---:|---:|---:|---:|
| 1 days | 81 | 1.04 | 1.95 | 46.3% | 92.6% |
| 3 days | 81 | 0.74 | 3.25 | 46.2% | 100.0% |
| 10 days | 81 | 0.41 | 4.11 | 35.6% | 100.0% |
| 30 days | 81 | 0.25 | 4.72 | 33.0% | 100.0% |

## One-factor sensitivity

| Factor | Cells | Dispatch range | Dominant-share range | Peak-threat range |
|---|---:|---:|---:|---:|
| aggression | 3 | 2–5 | 25.0%–36.4% | 16.92–24.85 |
| reserve | 3 | 3–5 | 36.4%–50.0% | 13.58–16.92 |
| distance | 3 | 2–9 | 36.4%–50.0% | 10.54–17.08 |
| idle | 3 | 8–9 | 34.8%–35.0% | 18.94–18.94 |
| learning | 3 | 4–5 | 25.0%–36.4% | 14.26–16.92 |
| supply | 3 | 5–5 | 36.4%–36.4% | 16.76–16.92 |

## Findings and candidate changes

### 1. Formulaic squad readiness

Across 318 dispatching longitudinal cells, mean squad size ranges from 2.00 to 2.58. 6 of 81 one-day cells do not dispatch yet; every three-day cell does.

Evidence: `h1d-e0-warband-favored-s11`, `h1d-e6-warband-favored-s11`.

Candidate: Retain the preference-selected-lead plus distinct-support readiness formula. Adjust mobilization or cooldown separately if first-contact cadence needs tuning.

Risk: Weakening readiness directly can reintroduce singleton raids; strengthening it can delay contact in low-habitability terrain. Confidence: **high**.

### 2. Low-level and mature roster variety

The worst three-day cell assigns 50.0% of members to one recruit; the envelope mean is 46.2%. 6 of 6 live recruits appear across mature cells, with 3–6 distinct recruits per cell.

Evidence: `h3d-e0-warband-favored-s11`, `h3d-e0-warband-favored-s29`, `h3d-e0-nominal-s11`.

Candidate: Retain the current combination of within-squad marginal utility and bounded, decaying deployment memory.

Risk: Too much penalty can force weak recruits despite strong environmental preferences. Confidence: **high**.

### 3. Material and equipment diversity

At least one mature cell extracts only 9 material type(s) and manufactures 5 equipment formulation(s).

Evidence: `h30d-e3-player-favored-s47`, `h30d-e7-player-favored-s11`, `h30d-e7-player-favored-s29`.

Candidate: Retain recent extraction frequency as a bounded utility term; compare the observed material floor against actual TCon compatibility before increasing diversity pressure.

Risk: Scarcity pressure must not select unaffordable or environmentally impossible materials. Confidence: **high**.

### 4. Pressure cadence

Observed cadence ranges from 0.07 to 2.00 dispatches per day across the bounded envelope.

Evidence: `h30d-e1-player-favored-s11`, `h1d-e7-warband-favored-s11`.

Candidate: Make cooldown recovery a smooth function of aggression, surviving threat, and recent completed-cycle duration.

Risk: Faster recycling can become oppressive when several warbands target one travel corridor. Confidence: **medium**.

### 5. Equipment expression

The weakest mature cell equips 100.0% of dispatched members while retaining 4 armory items. Mature environments manufacture 6–10 formulations each; 11 of 14 formulations are environment-specific rather than universal. Across mature cells the live functional mix is {defense=931, melee=586, ranged=676, utility=713}, and mean environment/recruit-weighted armament utility spans 1.64–3.57.

Evidence: `h30d-e0-warband-favored-s11`, `h30d-e0-warband-favored-s29`, `h30d-e0-warband-favored-s47`.

Candidate: Keep capability-gain and action-compatibility assignment; tune manufacturing throughput and wear before changing selection utility.

Risk: Aggressive stock rotation can erase the identity created by material preferences. Confidence: **medium**.

### 6. Idle-return aggression feedback

Idle-return sensitivity changes completed campaigns from 7 to 9 over ten days; final aggression spans 13–15.

Evidence: `sensitivity-idle-6000`, `sensitivity-idle-12000`, `sensitivity-idle-24000`.

Candidate: Keep the aggression increase on disengaged return, but scale it by idle duration relative to travel time instead of a flat increment.

Risk: Scaling too softly makes kiting a permanent suppression strategy; scaling too strongly creates runaway raid budgets. Confidence: **medium**.

### 7. Logistics pressure and recoverability

Across supply sensitivity, mean segment satisfaction spans 59.8%–73.2%. Nominal-aggression cells avoid lethal attrition, while the high-aggression sensitivity sustains 0 losses and retains 0 recoverable caches before withdrawing.

Evidence: `sensitivity-supply-0`, `sensitivity-supply-24`, `sensitivity-supply-96`, `sensitivity-aggression-18`.

Candidate: Tune provisioning demand and forage debt together; preserve the grace interval and aggression-scaled retreat threshold.

Risk: Over-supplying erases interception and preparation; under-supplying turns distant campaigns into automatic retreats. Confidence: **high**.

### 8. Environmental route expression

Formula-selected campaign routes average 18.00 chunks, spanning 18.00–18.00; route length and supply consumption now respond to the same sampled terrain observations.

Evidence: `h1d-e0-warband-favored-s11`, `h1d-e0-warband-favored-s29`, `h1d-e0-warband-favored-s47`.

Candidate: Keep noise-biome corridor sampling unloaded-safe and tune forage value against friction rather than adding preferred biome categories.

Risk: Too much forage utility produces implausible detours; too little makes environment cosmetic. Confidence: **medium**.

### 9. Unmaterialized warband power growth

Mean peak deployed threat grows from 11.87 at three days to 22.70 at thirty days, while mature recruit diversity rises to 4.72 types per cell.

Evidence: `h3d-e7-nominal-s47`, `h30d-e7-nominal-s11`.

Candidate: Retain reserve growth, learned threat, manufacturing, and aggression feedback as independent continuous inputs; avoid a discrete late-game tier switch.

Risk: Peak power can grow while cadence falls, so both dimensions must remain visible in balance reports. Confidence: **high**.

## Interpretation boundary

Minecraft pathfinding and combat are bounded observations. Economy, selection, learning, travel, return, and reconciliation are the same compiled Core used by Forge.
