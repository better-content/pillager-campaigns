# Pillager Campaigns Programmatic Balance Notes

Generated from catalog `forge-live-sha256:e71cd31ec5350bfdab2dd154e9f5883c4469e0e2025a0083e138af86f9833dfb` across 339 deterministic cells. Regenerate the full JSON, CSV, and Markdown evidence with the commands in `testing.md`.

## Variety-fix result

Dispatch readiness now derives its target from aggression, the preference-selected lead recruit, and the cheapest distinct support recruit. Repeated recruits receive diminishing marginal utility during squad planning. These are formulas over the live catalog and learned preferences, not kits, roles, or archetypes.

| Measure | Before | After |
|---|---:|---:|
| Mean squad size, dispatching longitudinal cells | 1.00 | 2.00 |
| Worst three-day dominant recruit share | 90.0% | 50.0% |
| Recruits appearing across mature cells | 3 of 6 | 6 of 6 |
| Mature distinct recruits per cell | 3 | 3–5 |

All 318 longitudinal cells that dispatched formed exactly two-member squads. Six of 81 one-day cells in the harshest sampled environment had not dispatched yet; every three-day cell dispatched.

## Longitudinal overview

| Horizon | Cells | Raids/day | Distinct recruits | Dominant share | Equipment coverage |
|---:|---:|---:|---:|---:|---:|
| 1 day | 81 | 1.30 | 2.07 | 46.3% | 46.3% |
| 3 days | 81 | 1.00 | 2.90 | 48.9% | 52.9% |
| 10 days | 81 | 0.60 | 3.41 | 39.3% | 61.8% |
| 30 days | 81 | 0.43 | 3.95 | 36.5% | 62.6% |

## One-factor sensitivity

| Factor | Dispatch range | Dominant-share range | Peak-threat range |
|---|---:|---:|---:|
| aggression | 2–6 | 25.0%–41.7% | 12.30–25.96 |
| reserve | 5–9 | 38.9%–41.7% | 11.98–13.26 |
| distance | 6–6 | 41.7%–41.7% | 12.30–12.30 |
| idle return | 9–28 | 28.6%–39.1% | 18.94–26.71 |
| learning | 4–6 | 37.5%–50.0% | 10.54–29.22 |

## Remaining balance notes

1. **Cadence is now lower but more substantial.** The mature envelope averages `0.43` raids/day versus `1.06` singleton raids/day before the fix. Tune mobilization or cooldown if playtests feel quiet; weakening readiness would directly reintroduce singleton raids.
2. **Long-term variety is materially better.** Mature cells average `3.95` distinct recruits and all six appear across the envelope. A bounded, decaying recent-deployment term remains a possible later improvement if players notice repetition across consecutive campaigns.
3. **Material choice still converges.** Some mature cells extract one material type and manufacture one formulation. Ledger abundance and recent extraction frequency remain the strongest next formula candidates.
4. **Equipment expression remains uneven.** The weakest mature cell equips `42.9%` of members despite retained stock. Capability gain, action compatibility, and bounded stock age should inform later assignment scoring.
5. **Idle-return pressure still works.** Idle-return sensitivity produces 9–28 dispatches over ten days, 28.6%–39.1% dominant share, and final aggression of 14–18.

Minecraft pathfinding and combat remain bounded observations. Economy, readiness, selection, learning, travel, return, and reconciliation use the same compiled engine as Forge.
