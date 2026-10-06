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

The goal is a combat solver for **all four Slay the Spire 1 characters** and a **reinforcement-learning (RL) agent**. RL research and training experiments are already underway. The current release supports Ironclad and colorless cards; Silent, Defect, and Watcher still require simulator work. The four-character solver and RL agent are active goals, not features of this release.

## Install

- [Subscribe on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843), or download the [v0.2.1 Windows ZIP](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.2.1).
- Windows 10/11 x64 only. Install ModTheSpire and BaseMod separately; see [Windows installation](WINDOWS_INSTALL.md). The package includes its native backend, so players need no WSL, separate Python, or source checkout.
- Developers can use the [native build guide](WINDOWS_PORTABLE.md). Linux, macOS, and Steam Deck builds are not available yet.

## Controls and privacy

`F10` / `Enter` toggles combat automation; `F9` stops search and execution; `F8` folds the panel. `F6` exports a local diagnostic report. Experience recording starts off on every launch; `Shift+F6` finalizes a recording you chose to start. Nothing uploads automatically, and the public build does not record audio. Review files before sharing them; see [Privacy](PRIVACY.md).
