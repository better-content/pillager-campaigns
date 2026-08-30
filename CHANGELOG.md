# Changelog

## 0.5.1

- Add permission-gated immediate scout and assault commands for client/server validation without waiting through the authored cadence.
- Keep forced encounters gated by recorded distant-terrain routing and normal materialization checks.
- Add structured live encounter inspection for route, wave, roster, targeting, and provenance verification.
- Replan around exact-pathfinding failures instead of retrying one deterministic approach anchor forever.
- Prefer distant origins backed by a recorded terrain chain so unrelated explored islands cannot starve a valid approach of its bounded search budget.

## 0.5.0

- Dispatch immaterial scout and assault groups from deterministic origins 512–768 blocks away while preserving the existing arrival windows.
- Persist a compact surface atlas from genuinely loaded chunks and route over that record without loading, ticketing, or generating terrain.
- Treat missing terrain as blocked, route around known terrain where possible, and stop at the exterior of known sealed walls or unwalkable cliffs.
- Materialize only into exact connected exterior positions; require a complete real path for open approaches and permit an unreachable real path only for a known defensive frontier.
- Migrate schema-2 clocks and outcomes to schema 3 while clearing active legacy near-player encounters.
- Add fixed-point travel, wall, cliff, unknown-terrain, cadence, actual-unload, sealed-base, real-mob, targeting, and provenance proofs.

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
