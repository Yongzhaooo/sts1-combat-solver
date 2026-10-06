# STS1 Combat Solver

[简体中文](README.zh-CN.md)

## Thanks and sources

Thanks to [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) for the C++ combat simulator and search foundation, [destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) for its modified `combat4r` and Python bindings, and [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) for the game communication bridge. Their pinned versions and licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

For ideas, [hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) informed the copy-and-restore approach to RNG foresight. We also studied [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) for its mirror simulation and action-verification design. These are *Slay the Spire 2* references; their code is not included here.

![Ironclad dreaming of the Spire front line](assets/ironclad-hospital-meme.jpg)

## What it does

A local combat advisor and optional executor for *Slay the Spire 1*. It searches Ironclad and colorless-card combat, compares potion lines, and checks executed actions against the game state. You choose cards, paths, shops, rest sites, and events. Search has a finite budget and does not guarantee an optimal line or perfect simulation. Mods that change combat rules may create unsupported states.

The panel supports English and Chinese. Optional seeded foresight previews events, encounters, and transforms using hidden RNG information beyond ordinary visible play. The [potion score table](POTION_POLICY.zh-CN.md) explains reward swaps and combat use.

## Project goals and progress

- **Four-character combat solver — underway:** The current release supports Ironclad and colorless cards. Silent, Defect, and Watcher need further simulator work.
- **Reinforcement-learning (RL) agent — underway:** Policy research, training, and evaluation experiments are in progress. An RL agent is not included in this release.

## Install

- [Subscribe on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843), or download the [v0.2.1 Windows ZIP](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.2.1).
- Windows 10/11 x64 only. Install ModTheSpire and BaseMod separately; see [Windows installation](WINDOWS_INSTALL.md). The package includes its native backend, so players need no WSL, separate Python, or source checkout.
- Developers can use the [native build guide](WINDOWS_PORTABLE.md). Linux, macOS, and Steam Deck builds are not available yet.

## How to use

- **Launch:** After Steam finishes downloading, choose **Play with Mods**. In ModTheSpire, enable BaseMod and STS1 Combat Solver, then start an Ironclad run. Remove old separate `CommunicationMod.jar` and `SteamStateExport.jar` files supplied with previous solver versions.
- **Read the panel:** Click **Language** to switch the panel between English and Chinese. In combat, click **Recalculate** to search; compare the displayed routes and potion choices. Search has a finite budget, so a route is a recommendation rather than a proof of optimal play.
- **Execute a route:** Click a completed, verified route to execute it, or use **Execute one step**, **Execute turn**, or **Auto combat** for the current result. `F10` / `Enter` toggles combat automation; `F9` or **Stop** halts search and execution; `F8` folds the panel.
- **Choose automation scope:** **Auto potion rewards** handles post-combat potion pickup or swaps. **Auto potions + across-combat** handles potion use and later fights. These are separate panel switches. Card rewards, paths, shops, rest sites, and events remain your choices. See the [potion policy](POTION_POLICY.zh-CN.md).
- **Report a problem:** Press `F6` to export a local diagnostic archive. Experience recording starts off on every launch; if you enable it, `Shift+F6` finalizes its files. Review files before sharing them. Nothing uploads automatically, and the public build does not record audio; see [Privacy](PRIVACY.md).
