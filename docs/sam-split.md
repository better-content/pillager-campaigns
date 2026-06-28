# Warbands and Movements Split Notes

SAM core currently exposes only movement contracts for this module. World reads and writes are main-thread only, and pillager-specific planning remains deterministic over immutable snapshots.

`SAM: Pillagers` exposes invasion movement semantics only. Pillager discovery persists warbands directly, and raids originate from warband rally chunks toward player targets.

`SAM: Villagers` is intentionally not implemented yet. If introduced later, it should define its own movement layer without reintroducing dormant pillager settlement materialization surfaces.
