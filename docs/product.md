# STS1 Combat Solver product spec: four characters

Last confirmed: not yet confirmed

This spec states the destination for the four-character version. The released coverage is in the [README](../README.md); the structure and route are in [Architecture](architecture.md); the current simulator inventory is in [Multi-character scope](multi-character.md).

## Purpose

Players of *Slay the Spire 1* install one local Mod and get bounded combat planning, with optional execution, for Ironclad, Silent, Defect and Watcher. The maintainer develops the Ironclad solver and the RL agent; Silent, Defect and Watcher are developed with outside collaborators, who can add a character without access to the game's source code or the maintainer's private truth engine.

## Expectations

### Character support

- A character is shown as supported in both READMEs and in the panel only after its acceptance set passes: every native parity fixture for that character passes in public CI, the maintainer's private truth recheck of those fixtures passes, and one live run of that character's combat completes without a verification stop caused by the solver (proposed).
- In a run with a character that is not yet supported, the panel says the combat engine does not support that character yet. No search starts and nothing crashes.
- In a supported character's combat, a card, relic, power or potion the simulator does not implement is reported as a limitation, naming the item, before search starts. The simulator never reaches an unimplemented branch mid-search.
- Ironclad behavior does not regress. Every existing Ironclad check and fixture keeps passing on each change.

### Combat solving

- Every supported character uses the Ironclad contract: bounded search, a plan in the panel, optional execution, and verification of each observed result. Execution stops on disagreement.
- Character state that affects combat (orbs and their values, Focus, stance, mantra, retained cards, scry and discard choices) is captured from the game, imported into the simulator and verified the same way as HP, block and piles.
- Card and relic behavior follows the original game's Java logic. When a contributor's description and the Java behavior disagree, Java wins.

### Truth calibration and fixtures

- The maintainer publishes calibration cases in the repository: a seed, character and ascension, an action sequence, and the expected game observations. A truth engine that reproduces every case exactly counts as calibrated for that case set.
- Native parity fixtures (combat start state, actions, expected states) can be produced by any calibrated truth engine. Public CI checks the native simulator against them without Java or the game.
- Before a contributed fixture is merged, the maintainer replays it on the private truth engine. A disagreement blocks the merge until the source of the difference is known (proposed).

### Collaboration

- Both READMEs and the repository description say that collaborative development is welcome and give a contact address.
- A contributor can build the native part, run its tests and pass public CI without owning the game. Java-side checks and live play need the contributor's own copy of the game, ModTheSpire and BaseMod.
- [CONTRIBUTING](../CONTRIBUTING.md) states the code and CI requirements; [Truth calibration](truth-calibration.md) states the case format and the adapter protocol.

## Non-goals

- Distributing the game JAR, decompiled game source, or any truth engine (the maintainer's or an upstream one), including through fixtures or patches.
- Remote or online solving.
- Changing Ironclad's search objective or outside-combat rules as part of four-character work.
- Supporting Mods that change combat rules.
- Full autopilot for the new characters in their first supported release (proposed; see Open questions).

## Open questions

- Order after Silent: Watcher or Defect first. Blocks the order of the second and third character milestones.
- Whether the new characters get full autopilot, and with what evaluation. Blocks outside-combat rule and model work for them.
- Size and composition of each character's acceptance set. Blocks turning on that character's support switch.
- Integration branch for outside pull requests: `main` (proposed) or the current fix branch. Blocks the first outside PR.
