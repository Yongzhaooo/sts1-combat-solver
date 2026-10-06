# STS1 Combat Solver — Ironclad

A local combat solver for Slay the Spire 1. You build the deck and choose the route; it searches the current fight, compares potion lines, and can execute one action, a turn, or combat automatically. The panel supports English and Chinese. Card, relic and enemy names follow the game language.

## Installation

The package targets Windows 10/11 x64 and includes a native backend. No WSL, Python installation, compiler or source checkout is needed. The Workshop item is not yet published.

Subscribe to this item, [ModTheSpire](https://steamcommunity.com/sharedfiles/filedetails/?id=1605060445) and [BaseMod](https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019), then wait for Steam to finish downloading. Choose Play with Mods in Steam. In ModTheSpire, enable BaseMod and STS1 Combat Solver. Communication and state export are integrated into the solver. Remove the previous separate CommunicationMod.jar and SteamStateExport.jar copies supplied with older solver versions.

SuperFastMode, SaveStateMod and other nonessential mods are not required. Mods that change combat rules can create unsupported states. Linux, macOS and Steam Deck packages are not available yet.

## Use and limitations

Current support covers Ironclad and colorless cards only. Search uses a finite budget and does not guarantee an optimal solution or perfect simulator parity. You still choose card rewards, paths, shops, rest sites and events. Foresight reads seeds and RNG states to reveal outcomes beyond ordinary visible information.

- Click Language to change the panel language; the choice persists after restart.
- F10 / Enter toggles combat automation; F9 stops search and execution; F8 folds or expands the panel.
- F6 exports a local diagnostic archive. Inspect its contents before sharing.
- Experience recording starts Off on every launch. After enabling it manually, Shift+F6 finalizes and opens complete, losslessly compressed experience parts.

Logs and experience files live in `%LOCALAPPDATA%\STS1CombatSolver`, outside Workshop updates. Nothing uploads automatically and the public build does not record audio. Bug reports do not grant AI training permission; experience files require a separate explicit agreement before training use. A training-data submission channel is not open yet: keep recordings locally and do not post files containing personal information in Workshop discussions. See the included PRIVACY.md for details.

## Development goals

The long-term goal is a Slay the Spire RL agent. Discussion, feedback and code contributions are welcome. Supporting The Silent, The Defect and The Watcher requires rebuilding the simulator. These and RL are development goals without a promised release date.
