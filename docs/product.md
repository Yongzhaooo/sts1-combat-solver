# STS1 Combat Solver product spec

Last confirmed: not yet confirmed

This spec states the destination for the four-character version and the player's control modes. The released coverage is in the [README](../README.md); the structure and route are in [Architecture](architecture.md); the current simulator inventory is in [Multi-character scope](multi-character.md).

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

### Play modes and player control

Player request (七月流火, 2026-10-21), cleaned, and the maintainer's decisions of 2026-10-21 and 2026-10-23. Items marked (proposed) are not yet decided.

- There are three modes, exactly one active: Standby (the player starts each fight's search and approves its plan), Combat auto (the player owns route, rewards, shops and events; fights run automatically) and AI autopilot (route, rewards and fights automatic). Standby replaces the former Manual step-by-step mode; there is no separate standby setting.
- The mode is chosen at run start (Standby, Combat auto or AI autopilot) and can be switched at any time with one click on a three-way switch in the combat panel. F10 plays or pauses the current fight in every mode. F9 always stops and returns to Standby. Switching never advances an action by itself (proposed).
- Search tier (Standard, High-speed, Streak) is independent of the mode; changing one leaves the other unchanged (proposed).
- In Combat auto, a "no potions" switch makes the solver search and execute only potion-free lines. The potion slots after the fight equal those before it. When no potion-free line wins, execution stops before the first action and the panel says so; it never falls back to a potion line (proposed).
- Standby in combat: when a fight starts the panel is folded and nothing is searched. Solve (or F10) starts the search; the panel stays folded and then shows the predicted result with Play. Play runs the whole fight; Pause stops it. When the fight ends the panel folds again and the next fight waits for Solve.
- Standby never plays an unapproved plan. With one usable plan, Play is the approval. Whenever more than one plan is usable, the panel unfolds and lists them before any action: the potion-free line, plus potion lines, each with predicted HP loss, win or not, revivals spent and bottles gained. Nothing runs until the player picks one (a click, or its number then Enter); the panel then folds back and Play runs the picked plan. Listing executes nothing and consumes nothing. A potion-free win without HP loss is not interrupted: the search does not try bottles then, so there is nothing to choose. Combat auto and AI autopilot keep choosing by themselves.
- Finished plans are clickable while the search continues. In Standby a click stops the search and picks that plan, which then waits for Play; in Combat auto and AI autopilot it plays that plan and keeps the mode. After F9 the plan found so far stays on the panel.
- In Standby, Enter does nothing; Step in the unfolded panel plays one action. In Combat auto, Step, Pause auto or F10 take over the current fight only; the next fight runs automatically again and the mode stays Combat auto.
- Standby outside combat: route, rewards, shops and events are the player's; no suggestion card appears.
- Every clickable control and plan row brightens under the pointer; rows that cannot be clicked do not.
- Warnings about other mods (incompatible save/undo mods, RNG-changing mods, SuperFastMode above the tested speed) stay on screen in red, whole, in their own box under the panel, in every panel state.
- After the player approves a plan, the solver plays the fight under that plan's potion constraint. A divergence from the prediction re-plans under the same constraint without asking again; an approved potion-free plan never drinks a bottle (proposed).
- The player can restrict the usable bottles for a fight, and the listed plans respect it. The search already accepts potion permissions; the panel has no control for it (proposed).

### Combat panel

Maintainer decisions of 2026-10-23: the combat panel shows as little as possible, details open on request, and each action has one way in. Visual language follows shadcn/ui (neutral card, one primary button, secondary and ghost buttons, badges, disclosure rows), drawn with the game's own font.

- The panel is 420 units wide and as tall as its content. Its header holds the title, Export, Settings and fold; under it sits the three-way mode switch.
- The folded panel is one row: title, one status badge (Standby, searching with seconds, predicted result, auto playing) and at most one action (Solve, Stop, Play, Pause).
- Unfolded, the panel shows the outcome in one line (win or not, final HP, equivalent HP loss), the next action, and a "Play order · N steps" row that is closed by default. Opening it lists the steps with their turn; the rows are for reading and execute nothing. The open or closed state lasts for the run.
- At most three action buttons show, chosen by state: with a plan, Play, Step and Recalculate; while playing, Pause; while searching, Stop search; on an error, Recalculate under a red box that shows the whole message.
- Search tier, automatic potion swap and the feather-heal choice live in Settings with the other switches; the panel has no row of toggles and no hint line. Keys are listed at the foot of Settings.
- Every text in the panel shows whole or wraps; Chinese wraps between characters and English between words.

## Non-goals

- Distributing the game JAR, decompiled game source, or any truth engine (the maintainer's or an upstream one), including through fixtures or patches.
- Remote or online solving.
- Changing Ironclad's search objective or outside-combat rules as part of four-character work.
- Supporting Mods that change combat rules.
- Full autopilot for the new characters in their first supported release (proposed; see Open questions).
- Run win rate as the acceptance measure for play-mode or panel changes. A run's result also depends on routing, rewards and the player; panel changes are accepted by the observable behavior above (proposed).

## Open questions

- Order after Silent: Watcher or Defect first. Blocks the order of the second and third character milestones.
- Whether the new characters get full autopilot, and with what evaluation. Blocks outside-combat rule and model work for them.
- Size and composition of each character's acceptance set. Blocks turning on that character's support switch.
- Scope of the Combat auto "no potions" switch: this fight or the whole run, and whether it covers elites and Bosses. Blocks where the switch lives and whether it is saved.
- Which constraints Standby plans vary beyond potion use (relic counters, Feed, fewest turns, keeping a bottle). Each adds a search before the fight starts. Blocks the size of the Standby plan list.
- Whether a re-plan after a divergence needs a new approval. Blocks the Standby execution flow.
- Who implements the play-mode changes: an outside contributor or the maintainer. Blocks whether a panel state and protocol guide is written for contributors.
