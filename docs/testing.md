# Pillager Campaigns Testing

`./gradlew verifyFast --no-daemon` runs Core, runner, Forge-facing unit tests, and the 90% line-coverage gates. `./gradlew verifyFull --no-daemon` adds the headless Forge GameTests.

## Deterministic Core gate

```sh
./gradlew -q :runner:run --args='example' > /tmp/invasion-runtime-spec.json
./gradlew -q :runner:run --args='mvp /tmp/invasion-runtime-spec.json build/invasion-readiness'
```

The runner proves scheduling, per-player fairness, scaling bounds, roster constraints, durable effects, and terminal lifecycle under explicit synthetic surface observations. It is not a Minecraft combat, AI, or terrain simulation.

For the installed pack roster, run `/pillager_campaigns export_runtime_spec` after registries load and pass the exported `pillager_campaigns/exports/invasion-runtime-spec.json` to the runner.

## Required live validation

- Use Survival in the Overworld with view and simulation distance four.
- Verify the first warning and materialization occur within 24,000–36,000 eligible ticks on viable surface terrain.
- Resolve the invasion and verify the next arrives within 24,000–48,000 eligible ticks.
- Go underground before a due invasion; verify it remains queued, warns on returning to the surface, and receives the full 600 surface-tick warning.
- Build a known two-block wall between an approach edge and the player; verify the solid-surface model does not traverse it and the squad appears on the outer frontier.
- Verify sampling does not load or generate a remote chunk.
- Travel away from a bed or base and verify pressure follows the current player rather than attacking the empty home.
- Clear successive invasions and verify intensity rises within `0..5`; die to one and verify intensity pressure falls and the death grace applies.
- Verify squads contain 3–8 members, optional recruits appear only when installed/unlocked, and support/elite recruits remain capped.
- Ignore or leave an invasion and verify remaining tagged entities retire within the configured idle/unavailable limits.
- Load a pre-0.3 world copy and verify the old strategic state is discarded with one explicit warning and the new player pressure track starts cleanly.

Forge GameTests specifically guard runtime roster revision, loaded-chunk-only observation, and the height-field wall model. Native combat feel remains a live playtest requirement.
