# Changelog

## 0.4.0

- Split surface pressure into silent 6–12 minute scouts and warned 60–120 minute three-wave assaults.
- Allocate the installed It Takes a Pillage and Savage & Ravage catalogue by scout and assault-wave role.
- Add nearby-player grouping, deterministic budgets, progress-gated waves, low-health spacing, downed withdrawal, and hard 8/tick, 24/second, 96-global caps.
- Require a complete loaded-only surface graph and real `Path.canReach()` before EVENT materialization; stale or unloaded routes pause without forcing chunks.
- Add a 1,024-seed policy corpus and authored-mod Forge GameTests with a real dummy player, collision/fluid/gate checks, targeting, provenance, and cleanup.
- Intentionally reset schema-1 state into campaign schema 2.

## 0.3.0

- Replace the warband simulation with a minimal per-player Overworld invasion director.
- Add guaranteed warning/cadence windows, bounded time-and-outcome scaling, and curated vanilla/optional pack squads.
- Path immaterial approaches over a budgeted loaded/cached solid-surface graph before transactional materialization.
- Remove factions, rallies, officers, garrisons, territory, economy, logistics, TConstruct armories, rewards, succession, tactical routing, and SAM integration.
- Remove mandatory Mantle and TConstruct dependencies.
- Intentionally reset pre-0.3 strategic saves into invasion schema 1.
