# Settlements and Movements Split Notes

SAM core owns logical settlements, resolved settlement sites, movement contracts, and bounded work budgets. Modules register archetypes, materializers, and movement types. World reads and writes are main-thread only and must be sliced through queued jobs; background work is limited to deterministic planning over immutable snapshots.

`SAM: Pillagers` maps existing pillager bases to SAM settlement nodes, uses deterministic planned base locations, and materializes bases through jigsaw materializers only after a loaded-footprint site is resolved. Invasions are SAM movements from a base settlement toward a player target.

`SAM: Villagers` is intentionally not implemented yet. It should use the same contracts for logical villages and trade movements, with caravan and boat movements able to target unresolved villages until those settlements need physical resolution.
