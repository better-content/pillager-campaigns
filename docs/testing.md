# Pillager Campaigns Testing

`./gradlew verifyFast --no-daemon` runs the fixed 1,024-seed Core corpus, runner, Forge-facing unit tests, and the Core 90% line-coverage gate. `./gradlew verifyFull --no-daemon` adds headless Forge GameTests. `./gradlew verifyWorld --no-daemon` runs the Forge lane with the exact authored roster mods.

## Deterministic Core gate

```sh
./gradlew -q :runner:run --args='example' > /tmp/invasion-runtime-spec.json
./gradlew -q :runner:run --args='mvp /tmp/invasion-runtime-spec.json build/invasion-readiness'
```

The runner proves scheduling, per-player fairness, scaling bounds, roster constraints, durable effects, and terminal lifecycle under explicit synthetic surface observations. It is not a Minecraft combat, AI, or terrain simulation.

For the installed pack roster, run `/pillager_campaigns export_runtime_spec` after registries load and pass the exported `pillager_campaigns/exports/invasion-runtime-spec.json` to the runner.

## Required live validation

- Use Survival in the Overworld with view and simulation distance four.
- Verify silent scouts arrive within 7,200–14,400 eligible ticks and contain 3–6 ranged/line recruits.
- Verify assaults arrive within 72,000–144,000 eligible ticks and receive the full 2,400 surface-tick warning.
- Verify each assault has three 8–12-member waves and advances after half the prior wave falls or after 1,500 ticks.
- Go underground before delivery; verify both clocks continue for an eligible Overworld Survival player but delivery waits for viable surface terrain.
- Build a known two-block wall between an approach edge and the player; verify the solid-surface model does not traverse it and the squad appears on the outer frontier.
- Verify sampling does not load or generate a remote chunk.
- Travel away from a bed or base and verify pressure follows the current player rather than attacking the empty home.
- Clear successive invasions and verify intensity rises within `0..5`; die to one and verify intensity pressure falls and the death grace applies.
- Verify support and elite recruits remain capped at one per wave, a final-wave elite appears at intensity 3+, and ravagers appear only at intensity 5.
- Verify no tick exceeds 8 spawns, no rolling second exceeds 24, and tracked campaign population never exceeds 96.
- Ignore or leave an invasion and verify remaining tagged entities retire within the configured idle/unavailable limits.
- Load a pre-0.3 world copy and verify the old strategic state is discarded with one explicit warning and the new player pressure track starts cleanly.

Forge GameTests create a real Survival dummy player at an explicit Y, exercise open and closed navigation gates with `Path.canReach()`, reject solid/fluid occupation, prove remote chunks remain unloaded, and verify EVENT spawn targeting/provenance/cleanup. Native combat feel remains a live playtest requirement.
