# Combat Solver architecture

Last confirmed: not yet confirmed
Product spec: [Four-character product spec](product.md); released coverage and player-facing behavior remain in the [README](../README.md#what-it-does).

This document describes the target component boundary, including four-character support, and the route to it. The current simulator inventory, with file and line pointers, is in [Multi-character scope](multi-character.md).

## Target structure

The Mod runs inside the game JVM. `overlay/src/sts1solver` owns the panel, game-state capture, legal menu interaction and action execution. `SolverMod` coordinates these operations; `BackendRuntime` locates and launches the platform runtime. The game remains the authority for actual state and action results.

The Python backend in `overlay/backend.py` owns the Mod request protocol, worker lifecycle, game-state import, settings and execution checks. `overlay/solver_search.py` owns bounded search stages and final plan reduction through `search_plan` and `finalize_plan`. Its inputs are an imported native root, explicit game context, budget, potion permissions and cancellation signal. It returns a plan and does not import the Mod protocol, diagnostics or outside-combat policy. `overlay/plan_reduction.py` replays candidate reductions and accepts them only through the native victory/resource guard. Existing `Advisor` methods delegate to this shared implementation so consumers retain their API.

The native simulator under `candidates/` and the search extension in `overlay/native.cpp`, `loop_beam.h` and `growth_gate.h` form the common combat engine. The overlay path is a source location, not a dependency on the Java panel. Mod and collection consumers must use the same accepted source revision for these components. Product settings such as Feed weighting and Incense targets belong to the caller and are not implicitly copied into a frozen collection profile. A simulated state belongs to one request; the live game remains authoritative. Execution checks the observed result and stops on unsupported states or disagreement.

`overlay/runtime_files.py` is the shared Python runtime inventory for Windows and macOS packaging. It separates search modules from Mod modules, and worker reload watches the same overlay list. Collection packaging can consume `SEARCH_FILES` and the native/bridge artifacts without importing `backend.py`. The previously frozen π_base v2.6 package retains its original adapter and hashes; this extraction does not mutate an in-flight collection or relabel old data. Future collection builds should consume the shared search API instead of copying method bodies from the Mod backend.

The collection harness also has an explicit `headless_xs128` end-turn shuffle profile. The Mod accepts the live game's `basemod_seeded` and `java_shared` profiles. Share the search algorithm, but retain each environment's importer and rule profile; changing a profile to make a fixture load invalidates the comparison. Cross-consumer checks use the same frozen routes with their original profile.

Full autopilot uses `OutsidePacket`, `Distill2`, `RoutePlanner` and the local rule classes to choose outside-combat actions. Menu ownership and legal execution stay in the Mod. The [outside rules](outside-rules.md) document the current decision chain; [event rules](event-rules.md) cover event handling. Optional foresight has a separate information boundary described in the README.

Diagnostics stay on the player's machine until the player exports and shares them. [Privacy](../PRIVACY.md) defines the data boundary. Release packages contain the Mod and a matching platform runtime; game JARs and private research sources are excluded. [Third-party notices](../THIRD_PARTY_NOTICES.md) identify bundled sources and licenses.

### Four-character components (proposed)

- **Character registry.** One list of supported characters, read by `backend.py`, `SolverMod` and `OutsidePacket`. Today the gate is copied in three places and kept in sync by comments; a check asserts they agree until the list is shared.
- **Coverage manifest.** A generated table of implemented and missing cards, relics, powers and potions per character, derived from the card pools and the card dispatch. The backend uses it to refuse an unsupported item before search, and CI uses it to refuse a supported character with a gap.
- **Simulator core and character modules.** The shared combat core keeps turn flow, damage, piles, RNG and search. Each character's card behavior lives in its own dispatch function or file (for example `useSilentCard`), called from the shared `use*Card` switch, so contributors working on different characters do not edit the same `case` lists. Shared subsystems (discard selection, scry, stances, orbs) are added once and reused. Player status capacity is checked at compile time.
- **Snapshot schema.** The Mod export, the simulator importer and step verification share one versioned schema. Optional fields for orbs, Focus, stance, mantra and retained cards are added in one release, with producer and consumer changed together. The importer reads the character from the snapshot instead of assuming Ironclad.
- **Calibration toolchain.** `tools/truth_calibration/` holds the case schema, the runner that drives any truth engine through the adapter protocol, a reference adapter for a headless-engine CLI, and the comparison, which reuses the strict comparator in `candidates/sts-ironclad-agent/sim_patch/parity/core.py`. Calibration cases and native parity fixtures are versioned under `fixtures/`. The maintainer's exporter that produces calibration cases runs on the private truth engine and is not part of this repository. Format and flow: [Truth calibration](truth-calibration.md).
- **CI.** Public CI on GitHub Actions runs on every pull request: native build and CTest, the existing offline checks, the coverage manifest check, and replay of every native parity fixture against the simulator. It needs neither Java nor the game. The maintainer's private CI replays newly contributed fixtures on the private truth engine before merge. Requirements for contributors are in [CONTRIBUTING](../CONTRIBUTING.md).

## Approach

Keep Java game integration and the native search worker separate (proposed). This preserves the existing game hooks and platform builds, and lets a failed worker be handled without transferring game-state ownership to another process. Replacing them with a server would add deployment and privacy costs without an established product need.

Reuse the existing runtime builder, packaging checks and CI matrix. Source synchronization, portable package validation and Workshop publication are separate steps; a Git push does not install or publish a release.

Four-character work stays in this repository rather than a new one, and `main` is the integration branch for outside pull requests. The characters share the simulator core, the import bridge, the Mod capture, packaging and CI; a second repository would fork all of them and drift. Native rule fixes found by the maintainer's research base are ported here as they are accepted, so contributors start from the current rules.

Truth stays private, and calibration replaces sharing it. Contributors build their own truth engine from their own copy of the game and calibrate it against published cases; the alternative, giving contributors the maintainer's decompiled engine, would redistribute game code. Calibration cases start from a seed rather than a mid-combat save, so a contributor's engine needs only "start a run, take an action, report the observation", not the maintainer's save import. The contributor's calibrated engine then produces native fixtures for new mechanics, and the maintainer's recheck catches any calibration gap the published cases did not cover.

## Route

1. Reliable Ironclad delivery (proposed): bounded search, cancellation, worker failure reporting and state verification remain testable through existing backend checks. Live-game reproduction must confirm fixes that depend on animation or menu timing. Version 0.6.0 ports the accepted native rule fixes from the research base.
2. Autopilot release confidence (proposed): validate each menu owner and recovery path in the client, retain manual takeover, and distinguish offline checks from complete live runs. Policy changes require their own evaluation evidence before a package adopts them.
3. Four-character foundation, M0 (proposed): no change in player behavior. Character registry or consistency check; coverage manifest and pre-search refusal of unimplemented items; per-character dispatch files; status capacity increase with a compile-time check; snapshot schema with optional character fields; importer reads the character; the calibration toolchain with a first published case set covering all four characters; CI workflow fixed to run only scripts that exist. Acceptance: all Ironclad checks unchanged, the new checks pass, and the maintainer's truth reproduces the published cases.
4. Silent, M1 (proposed first character; see [Multi-character scope](multi-character.md#3-里程碑顺序proposed) for the sub-steps): starting cards and Shivs, poison cards, discard selection, class relics and potions, then the support switch once the acceptance set passes.
5. Watcher and Defect, M2 and M3 (proposed; order open): stances, mantra, scry and generated cards for Watcher; the orb subsystem with Focus and RNG alignment for Defect. Each switch opens only after its acceptance set passes.

Irreversible points: the snapshot schema version change in M0 (old diagnostic exports must still load, or the loader must say they are from an older version), and any renumbering of player status values (all fixtures and builds that store them move together). Keep prior release packages available for rollback. Any incompatible request or diagnostic format change must update both producer and consumer in the same release and document compatibility before publication.

## Open decisions

- The acceptance set and release order for Silent, Defect and Watcher; they block a four-character release claim.
- Whether the new characters get full autopilot; it blocks outside-combat work for them.
- Linux and Steam Deck portable-runtime delivery remains undecided; source-build support does not establish a bundled release.
