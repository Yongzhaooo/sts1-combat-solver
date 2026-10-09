# STS1 Combat Solver

[简体中文](README.zh-CN.md)

## Thanks and sources

Thanks to [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) for the C++ combat simulator and search foundation, [destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) for its modified `combat4r` and Python bindings, and [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) for the game communication bridge. Their pinned versions and licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

For ideas, [hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) informed the copy-and-restore approach to RNG foresight. We also studied [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) for its mirror simulation and action-verification design. These are *Slay the Spire 2* references; their code is not included here.

![Ironclad dreaming of the Spire front line](assets/ironclad-hospital-meme.jpg)

## What it does

A local combat advisor and optional executor for *Slay the Spire 1*. It searches Ironclad and colorless-card combat, compares potion lines, and checks executed actions against the game state. By default you choose cards, paths, shops, rest sites, and events; the optional full autopilot (below) uses route rules and a network for other decisions. Search has a finite budget and does not guarantee an optimal line or perfect simulation. Mods that change combat rules may create unsupported states.

The panel supports English and Chinese. Optional seeded foresight previews events, encounters, and transforms using hidden RNG information beyond ordinary visible play. The [potion score table](POTION_POLICY.zh-CN.md) explains reward swaps and combat use.

## Project goals and progress

- **Four-character combat solver — underway:** The current release supports Ironclad and colorless cards. Silent, Defect, and Watcher need further simulator work.
- **Reinforcement-learning (RL) agent — underway:** Policy research, training, and evaluation experiments are in progress. v0.4 ships an early training result that drives the autopilot below.

## Full autopilot (early)

> **This is an early training result and its play is not good yet.** On a small dataset it wins roughly 59%–81% of A20 runs. Known weaknesses: it upgrades too rarely, its deckbuilding lacks a plan, and it walks into early elites at full HP. Treat it as an experiment, not a reliable winner.

- **Opt-in per run:** When an Ironclad run starts, the solver asks whether to enable it: full auto, step mode (each decision is shown and waits for confirmation), or manual. It is off by default; press `F9` or click Take over at any time.
- **What it decides:** Neow, card rewards, campfires, shops, events, chests and boss relics are scored by a small network, with candidates and scores shown on the panel. Combat uses the existing solver. The network runs inside Java and needs no extra install.
- **Map route:** Reviewed rules choose the next node and draw the same connected route to the boss. Act 1 favors preparation before elites, Act 2 favors events and useful shops, and Act 3 includes the burning elite when the green key is missing. Low HP reduces elite preference; each new state can change the route. No whole-act damage-budget veto suppresses a legal plan. This is a preference heuristic, not a survival prediction.
- **Removal limits:** Removal and transforms only pick curses, Strikes, Defends and Bash.
- **Deck changes:** While autopilot runs, cards it gains, upgrades or removes appear under the deck icon for one second.

## Install

- [Subscribe on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843), or download a [v0.4.2 package](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.4.2).
- Resize the panel with the − / + buttons on its Settings page; click the percentage to reset. The 60–160% setting persists across launches and changes only the overlay.
- The Workshop package supports Windows 10/11 x64 and macOS 11+ (Apple Silicon and Intel). Install ModTheSpire and BaseMod separately, choose Play with Mods, and enable BaseMod plus STS1 Combat Solver. No separate Python, compiler, WSL or source checkout is needed. See [Windows installation](WINDOWS_INSTALL.md) or [Mac installation and release checks](MACOS_INSTALL.md).
- Windows, macOS and Linux source builds remain available below. Linux/Steam Deck have no bundled Workshop runtime yet.

### Native source build (Windows / macOS / Linux)

Prerequisites: Python 3.11+, CMake 3.19+, a C++17 compiler, JDK 9+ (`java`, `javac`, `jar`), Slay the Spire, ModTheSpire and BaseMod. On macOS install Xcode Command Line Tools; on Windows use Visual Studio C++ Build Tools. Build separately on each OS/CPU architecture with matching Python/compiler architecture. Do not copy `.pyd`/`.so` files or a virtual environment between platforms.

Run from the repository root. On macOS/Linux:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install pybind11==3.1.0
.venv/bin/python tools/build_solver_runtime.py
.venv/bin/python tools/check_backend_launcher.py
.venv/bin/python tools/check_backend_protocol.py
.venv/bin/python overlay/build.py --game "/path/to/steamapps/common/SlayTheSpire"
```

On Windows (PowerShell), use the same commands with `.venv\Scripts\python.exe`:

```powershell
py -3 -m venv .venv
.venv\Scripts\python.exe -m pip install pybind11==3.1.0
.venv\Scripts\python.exe tools/build_solver_runtime.py
.venv\Scripts\python.exe tools/check_backend_launcher.py
.venv\Scripts\python.exe tools/check_backend_protocol.py
.venv\Scripts\python.exe overlay/build.py --game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
```

`--game` is the Steam game directory, including the directory *containing* `SlayTheSpire.app` on macOS. The builder discovers Workshop dependencies in the same Steam library; for another library or manual installation, pass `--modthespire /path/to/ModTheSpire.jar --basemod /path/to/BaseMod.jar`.

The output is `overlay/build/STS1CombatSolver.jar`. Exit the game before installing it in your game's `mods` directory (on macOS, the launch directory is commonly `SlayTheSpire.app/Contents/Resources`). Disable the Workshop solver and move any old solver JAR outside loaded mod directories first: load only one solver. Leave BaseMod/ModTheSpire installed. The script does not change installed mods or saves.

This is a **local source installation**, not a portable release: the JAR records the absolute interpreter and backend paths. Keep the checkout and `.venv` in place; rebuild the JAR after moving either. Do not distribute this locally configured JAR. The old Windows bundled/WSL workflows remain available; `overlay/build.py --bundled-windows --game ...` builds a JAR for the existing Windows packager.

Logs and diagnostic files live in `%LOCALAPPDATA%\STS1CombatSolver` on Windows, `~/Library/Application Support/STS1CombatSolver` on macOS, and `${XDG_STATE_HOME:-$HOME/.local/state}/STS1CombatSolver` on Linux. A relative `XDG_STATE_HOME` is ignored. Legacy WSL builds retain their configured log directory.

CI covers Python 3.11 and 3.14 on Windows, macOS and Linux, plus separately built Apple Silicon and Intel portable packages. It checks native search/replay, real worker requests and cancellation, diagnostic retention/export, localization and runtime selection. Mac package checks use production Java extraction and the bundled Python in a relocated Unicode path with a minimal PATH. These checks do not prove full live-game parity. CI does not redistribute proprietary game JARs.

## How to use

- **Launch:** After Steam finishes downloading, choose **Play with Mods**. In ModTheSpire, enable BaseMod and STS1 Combat Solver, then start an Ironclad run. Remove old separate `CommunicationMod.jar` and `SteamStateExport.jar` files supplied with previous solver versions.
- **Read the panel:** Switch the panel between English and Chinese on its **Settings** page. In combat, click **Recalculate** to search; compare the displayed routes and potion choices. Search has a finite budget, so a route is a recommendation rather than a proof of optimal play.
- **Execute a route:** Click a completed, verified route to execute it, or use **Execute one step**, **Execute turn**, or **Auto combat** for the current result. `F10` / `Enter` toggles combat automation; `F9` or **Stop** halts search and execution; `F8` folds the panel.
- **Choose automation scope:** **Auto potion swap** handles post-combat potion pickup or swaps. **Continuous auto** handles potion use and later fights. These are separate panel switches. Card rewards, paths, shops, rest sites, and events remain your choices unless the full autopilot is on. See the [potion policy](POTION_POLICY.zh-CN.md).
- **Report a problem:** Press `F6` to export a local diagnostic archive. Experience recording starts off on every launch; if you enable it, `Shift+F6` finalizes its files. Review files before sharing them. Nothing uploads automatically, and the public build does not record audio; see [Privacy](PRIVACY.md).
