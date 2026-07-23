# AGENTS.md

This file provides guidance to coding agents working in this repository.

## Project

A Paper (Minecraft 1.21.11) plugin: craftable glass jars containing persistent, enterable miniature worlds. Built with paperweight-userdev, so code may use NMS (`net.minecraft.*`) internals directly. ProtocolLib is an optional soft dependency (compileOnly) used only by the `protocol` entity-preview backend.

## Workflow

Commit and push after every completed implementation.

## Commands

- Build: `./gradlew build` (also runs tests and paperweight remapping)
- Tests: `./gradlew test`
- Single test: `./gradlew test --tests "tr.erdemdev.worldInAJar.CellLayoutTest"` (add `--tests "...#methodName"` for one method)
- Run a dev server with the plugin: `./gradlew runServer` (run-paper; server files in `run/`)

Java 21 toolchain. Tests are JUnit 5 and cover only the pure/static-geometry classes — they run without a server.

## Architecture

All code lives in one package, `tr.erdemdev.worldInAJar`. `WorldInAJar` (plugin main) wires everything in `onEnable` and is the only place services are constructed.

Two worlds are involved: the "outside" world where jar blocks are placed, and one flat interior world (`world_in_a_jar` by default) where every jar gets a cell. `CellLayout` maps a jar's persistent cell index to interior coordinates along an expanding square spiral; `interior.allocation-stride` in config.yml fixes cell spacing so cells created under different `jar.scale` settings never overlap.

Service layer (each a field of the plugin main):

- **JarRepository** — persistence to `jars.yml` via an async single-thread saver; holds `JarRecord`s (immutable, with validated `JarAssembly` geometry cached) and allocates cell indices.
- **InteriorService** — owns the interior world, cell readiness, and a per-tick work queue for interior world edits (budgeted by `combination-blocks-per-tick` / `combination-max-millis-per-tick` in config); tracks player sessions inside jars and retains chunks it needs.
- **PreviewService** — the largest class. Builds viewer-specific, display-only virtual scenes in both directions: interior blocks rendered as block displays at the jar's exterior, and outside blocks rendered around occupants inside. Uses fingerprinting to rebuild block scenes only on change, chunk snapshots taken off-thread, and per-tick budgets for snapshots/chunk tickets.
- **PortalTransferService** — moves players, items, and entities through jar portals, with per-entity cooldowns; also computes a jar's `realWorldAnchor` (its placed blocks, dropped item, carrying player, or container).
- **TeleportPolicy** — marks plugin-issued teleports so the boundary guard in `JarListener` can block/redirect all external teleports crossing between the interior world and the outside; any new code path that legitimately crosses must call `policy.teleport(...)`. The guard and the in-jar respawn override are toggled by `isolation.teleports` / `isolation.respawns` in config.yml (read live, so /jar reload applies them).
- **JarBackService** — tracks continuous holding of jar items (config `jar.last-holder-minutes`) to persist a per-jar "last holder", and serves the `/jar back` chest menu that recalls those jars.
- **JarListener** — all Bukkit event handling (place/break/interact, jar combination) delegating to the services.
- **JarItems** — item stacks, PDC keys, and crafting recipes.

Entity previews are pluggable behind `EntityPreviewBackend` (selected by `entity-preview` in config.yml): the built-in mannequin backend uses `VirtualEntity` subclasses (client-side-only entities sent as raw packets, never registered with a world), while `ProtocolEntityPreview` mirrors real interior entities to outside viewers via ProtocolLib packet interception and NMS packets.

Pure geometry is deliberately separated from Bukkit state so it can be unit-tested and run off the server thread: `JarAssembly`/`JarPart` (validated multi-cell shapes), `CombinationPlanner` (attaching one jar assembly to another — explicitly documented as safe off-thread), `InteriorBoundary` (which interior blocks are barrier walls), `DoorwayCollision` (whether outside blocks seal a portal), `CellLayout`, and `ProtocolPreviewMath`. Keep new logic in this pure layer when it doesn't need live server state.

## Conventions

- Server-thread work that touches many blocks must go through a per-tick budgeted queue (see `InteriorService` world jobs) rather than running all at once.
- Recipe registration in `onEnable` removes old keys first so hot reloads are idempotent; follow that pattern for any new registered keys.
- `plugin.yml` version is expanded from Gradle's `version` at build time (`processResources`); don't hardcode it.
- Config keys are documented with comments in `src/main/resources/config.yml`; new settings should follow suit.
