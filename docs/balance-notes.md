# Pillager Campaigns Programmatic Balance Notes

Generated from catalog `forge-live-sha256:e71cd31ec5350bfdab2dd154e9f5883c4469e0e2025a0083e138af86f9833dfb` across 339 deterministic cells. Regenerate the full JSON, CSV, and Markdown evidence with the commands in `testing.md`.

## Longitudinal overview

| Horizon | Cells | Raids/day | Distinct recruits | Dominant share | Equipment coverage |
|---:|---:|---:|---:|---:|---:|
| 1 day | 81 | 3.22 | 1.86 | 71.7% | 30.8% |
| 3 days | 81 | 2.26 | 2.60 | 62.2% | 41.7% |
| 10 days | 81 | 1.36 | 2.96 | 52.8% | 48.6% |
| 30 days | 81 | 1.06 | 3.00 | 45.5% | 54.5% |

## One-factor sensitivity

| Factor | Dispatch range | Dominant-share range | Peak-threat range |
|---|---:|---:|---:|
| aggression | 10–10 | 50.0%–60.0% | 5.92–6.08 |
| reserve | 8–17 | 52.9%–62.5% | 5.92–6.06 |
| distance | 10–10 | 60.0%–60.0% | 5.92–5.92 |
| idle return | 9–28 | 81.5%–100.0% | 9.55–16.80 |
| learning | 10–12 | 40.0%–100.0% | 4.78–6.54 |

## Findings and candidates

1. **Squad formation collapses to minimum affordability.** Mean squad size is exactly `1.0` throughout all 324 longitudinal cells. Dispatch fires as soon as the pool can afford the cheapest recruit, before it accumulates toward the aggression budget. Separate dispatch readiness from affordability by waiting for a formulaic desired-threat threshold. This is the highest-confidence candidate.
2. **Roster variety remains narrow.** The worst three-day cell is 90% one recruit, and only three of six live recruits appear in any mature cell. Add a bounded marginal-utility penalty derived from members already selected and recent deployment share.
3. **Material choice converges.** Mature cells can extract only two material types and manufacture one formulation. Add bounded ledger-abundance and recent-extraction scarcity terms to material utility.
4. **Pressure cadence varies too widely.** The envelope ranges from `0.67` to `4.00` raids per day. Make cooldown recovery a smooth function of aggression, surviving threat, and recent cycle duration after fixing dispatch readiness.
5. **Equipment expression is weak.** The lowest mature equipment coverage is `48.8%` despite retained armory stock. Score assignment by capability gain, action compatibility, and bounded stock age.
6. **Idle aggression can run away.** Shortening idle return from 24,000 to 6,000 ticks raises ten-day completed campaigns from 8 to 27 and reaches maximum aggression. Scale the aggression increment by disengagement duration relative to travel time instead of applying one flat point per return.

Minecraft pathfinding and combat remain bounded observations. Economy, selection, learning, travel, return, and reconciliation use the same compiled engine as Forge.
