# STS1 Combat Solver — Ironclad

A local combat solver for Slay the Spire 1. You build the deck and choose the route; it searches the current fight, compares potion lines, and can execute one action, a turn, or combat automatically. The panel supports English and Chinese. Card, relic and enemy names follow the game language.

Version 0.3.1 refreshes the combat panel, adds saved panel sizing, and lets the solver resume from Headbutt's discard picker after manual play or recalculation. Windows and both Mac architectures remain bundled.

## Installation

The package supports Windows 10/11 x64 and macOS 11+ (Apple Silicon and Intel), with native backends included. No WSL, Python installation, compiler or source checkout is needed. [Subscribe on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843). On Mac, the first backend start extracts the matching runtime into your user data directory; allow a few seconds.

Subscribe to this item, [ModTheSpire](https://steamcommunity.com/sharedfiles/filedetails/?id=1605060445) and [BaseMod](https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019), then wait for Steam to finish downloading. Choose Play with Mods in Steam. In ModTheSpire, enable BaseMod and STS1 Combat Solver. Communication and state export are integrated into the solver. Remove the previous separate CommunicationMod.jar and SteamStateExport.jar copies supplied with older solver versions.

SuperFastMode, SaveStateMod and other nonessential mods are not required. Mods that change combat rules can create unsupported states. Linux and Steam Deck packages are not available yet.

## How to use

- Start an Ironclad run. Click **Language** on the panel to switch between English and Chinese.
- Use the top-right − / + buttons to resize the panel from 60% to 160%. Click the percentage to reset. The setting is saved and does not change the game resolution.
- In combat, click **Recalculate** and compare the completed routes and potion choices. Click a verified route to execute it, or use **Execute one step**, **Execute turn**, or **Auto combat** for the current result.
- `F10` / `Enter` toggles combat automation; `F9` or **Stop** halts it; `F8` folds the panel.
- **Auto potion rewards** and **Auto potions + across-combat** are separate switches for reward swaps and later fights. You still choose card rewards, paths, shops, rest sites, and events.
- `F6` exports a local diagnostic archive. **Record battle data** starts Off on every launch; after enabling it manually, `Shift+F6` exports its files. Inspect files before sharing.

Current support covers Ironclad and colorless cards only. Search uses a finite budget and does not guarantee an optimal solution or perfect simulator parity. Foresight reads seeds and RNG states to reveal outcomes beyond ordinary visible information.

Logs and experience files live in `%LOCALAPPDATA%\STS1CombatSolver`, outside Workshop updates. Nothing uploads automatically and the public build does not record audio. Bug reports do not grant AI training permission; experience files require a separate explicit agreement before training use. A training-data submission channel is not open yet: keep recordings locally and do not post files containing personal information in Workshop discussions. See the included PRIVACY.md for details.

## Work underway

- **Four-character combat solver:** Ironclad is available now; Silent, Defect, and Watcher need further simulator work.
- **Reinforcement-learning (RL) agent:** Policy research, training, and evaluation experiments are in progress. No RL agent is included in this release.

Discussion, feedback and code contributions are welcome.
