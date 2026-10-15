# Combat Solver architecture

Last confirmed: not yet confirmed
Product spec: [README](../README.md#what-it-does), including its project goals and full-autopilot behavior.

This document describes the current component boundary and a proposed delivery route. Product behavior and supported platforms remain in the README.

## Target structure

The Mod runs inside the game JVM. `overlay/src/sts1solver` owns the panel, game-state capture, legal menu interaction and action execution. `SolverMod` coordinates these operations; `BackendRuntime` locates and launches the platform runtime. The game remains the authority for actual state and action results.

The Python backend in `overlay/backend.py` receives requests from the Mod and manages combat work. The native C++ simulator and search code under `candidates/` produce bounded combat plans. A simulated state belongs to a search request; it cannot replace the live game state. Execution checks the observed result and stops on unsupported states or disagreement.

Full autopilot uses `OutsidePacket`, `Distill2`, `RoutePlanner` and the local rule classes to choose outside-combat actions. Menu ownership and legal execution stay in the Mod. The [outside rules](outside-rules.md) document the current decision chain; [event rules](event-rules.md) cover event handling. Optional foresight has a separate information boundary described in the README.

Diagnostics stay on the player's machine until the player exports and shares them. [Privacy](../PRIVACY.md) defines the data boundary. Release packages contain the Mod and a matching platform runtime; game JARs and private research sources are excluded. [Third-party notices](../THIRD_PARTY_NOTICES.md) identify bundled sources and licenses.

## Approach

Keep Java game integration and the native search worker separate (proposed). This preserves the existing game hooks and platform builds, and lets a failed worker be handled without transferring game-state ownership to another process. Replacing them with a server would add deployment and privacy costs without an established product need.

Reuse the existing runtime builder, packaging checks and CI matrix. Source synchronization, portable package validation and Workshop publication are separate steps; a Git push does not install or publish a release.

## Route

1. Reliable Ironclad delivery (proposed): bounded search, cancellation, worker failure reporting and state verification remain testable through existing backend checks. Live-game reproduction must confirm fixes that depend on animation or menu timing.
2. Autopilot release confidence (proposed): validate each menu owner and recovery path in the client, retain manual takeover, and distinguish offline checks from complete live runs. Policy changes require their own evaluation evidence before a package adopts them.
3. Four-character support (proposed): extend simulator coverage and action verification before exposing another character as supported. Unsupported states continue to report a limitation. The README records actual released coverage.

Keep prior release packages available for rollback. Any incompatible request or diagnostic format change must update both producer and consumer in the same release and document compatibility before publication.

## Open decisions

- The acceptance set and release order for Silent, Defect and Watcher are not established here; they block a four-character release claim.
- Linux and Steam Deck portable-runtime delivery remains undecided; source-build support does not establish a bundled release.