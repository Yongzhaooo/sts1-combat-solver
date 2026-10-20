# STS1 Combat Solver

[简体中文](README.zh-CN.md)

QQ community group: **232459269**. Share your experience and report issues.

F6 diagnostics include each observed shop visit's first and latest state: gold, ordered deck with card UUIDs, relics, potions, and removal selection/confirmation. Recording is automatic in manual and automatic play and follows the game save. Existing saves have no retrospective history; an in-progress visit is marked `current`.

`searchWorkers=0` in ModTheSpire's `STS1CombatSolver/config.properties` selects the Mod backend's CPU limit automatically: half the available logical CPUs, at most 8, leaving at least 2 available CPUs where possible. Set `searchWorkers=1` through `32` to override it, still subject to that CPU reserve, then restart the game. The search child runs at lower priority where supported. Extra workers overlap independent potion branches; they do not speed up a single no-potion search. The search algorithm, budget and collection settings are unchanged. Reduce this setting if gameplay stutters. The effective limit is included in backend replies.

## Thanks and sources

Thanks to [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) for the C++ combat simulator and search foundation, [destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) for its modified `combat4r` and Python bindings, and [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) for the game communication bridge. Their pinned versions and licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

For ideas, [hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) informed the copy-and-restore approach to RNG foresight. We also studied [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) for its mirror simulation and action-verification design. These are *Slay the Spire 2* references; their code is not included here.

Special thanks to 七月流火（猫火） for long-term testing and feedback. Playing by hand with the solver, 七月流火（猫火） recently reached a streak of about 80 wins (SL-equivalent) and is close to 100.

![Ironclad dreaming of the Spire front line](assets/ironclad-hospital-meme.jpg)

## What it does

A local combat advisor and optional executor for *Slay the Spire 1*. It searches Ironclad and colorless-card combat, compares potion lines, and checks executed actions against the game state. By default you choose cards, paths, shops, rest sites, and events; the optional full autopilot (below) uses route rules and a network for other decisions. Search has a finite budget and does not guarantee an optimal line or perfect simulation. 七月流火（猫火）'s play shows it still trails top human players on some special lines, such as ordering cards for Burning Pact, Snecko Eye, and small loops it sometimes fails to find. Mods that change combat rules may create unsupported states (`SaveStateMod` and `Undo` / `undothespire` alter native discovery card generation, rewrite action queues, or desynchronize RNG state counters, and are not compatible).

The panel supports English and Chinese. Optional seeded foresight previews events, encounters, and transforms using hidden RNG information beyond ordinary visible play. Its Glowing Eye setting (off by default) controls the map rooms ahead, event-fight rewards and what each Neow blessing would give; Neow's four offers are shown as the game shows them, and other event previews are not gated by it. The [potion score table](POTION_POLICY.zh-CN.md) explains reward swaps and combat use.

## Project goals and progress

The product contract is described here; component boundaries and the proposed delivery route are in [Architecture](docs/architecture.md).

- **Ironclad solver and reinforcement-learning (RL) agent — maintainer focus:** The current release supports Ironclad and colorless cards. Policy research, training, and evaluation experiments are in progress; v0.4 ships an early training result that drives the autopilot below.
- **Silent, Defect, and Watcher combat solvers — collaborators welcome:** Most of their cards and mechanics are not yet implemented in the simulator. See the [multi-character scope](docs/multi-character.md) (Chinese) for the current state and work split, and [Contributing](CONTRIBUTING.md) for code and CI requirements. Contact: yongzhao.derek@gmail.com.

## Full autopilot (early)

> **This is an early training result and its play is not good yet.** In the training environment it wins about 73% of A20 three-key Heart runs over 2,400+ seeds (about 71% with the faster v0.9 search). Known weaknesses: it upgrades too rarely, its deckbuilding lacks a plan, and it walks into early elites at full HP. Treat it as an experiment, not a reliable winner.

- **Three modes, chosen per run:** When an Ironclad run starts, the solver asks which to use. **AI autopilot** climbs and fights; **Combat auto** runs every fight automatically while you make all other decisions; **Manual (step by step)** covers fights and everything outside them: the solver and the network suggest, and each step waits for `Enter` or a click. AI autopilot is off by default; press `F9` or click Stop at any time to return to Manual. Hide the suggestions card with its x to play outside combat without suggestions.
- **What it decides:** Neow, card rewards, campfires, shops, events, chests and boss relics are scored by a small network, with candidates and scores shown on the panel. Combat uses the existing solver. The network runs inside Java and needs no extra install.
- **Map route:** Reviewed rules choose the next node and draw the same connected route to the boss. Act 1 favors preparation before elites, Act 2 favors events and useful shops, and Act 3 includes the burning elite when the green key is missing. Low HP reduces elite preference; each new state can change the route. No whole-act damage-budget veto suppresses a legal plan. This is a preference heuristic, not a survival prediction.
- **Removal limits:** Removal and transforms only pick curses, Strikes, Defends and Bash.
- **Deck changes:** While autopilot runs, cards it gains, upgrades or removes appear under the deck icon for one second.

## Install

- [Subscribe on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843), or download a [v0.4.2 package](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.4.2).
- Resize the panel with the − / + buttons on its Settings page; click the percentage to reset. The 60–160% setting persists across launches and changes only the overlay.
- The Workshop package supports Windows 10/11 x64 and macOS 11+ on Apple Silicon (Intel Macs are not supported from v0.9.0). Install ModTheSpire and BaseMod separately, choose Play with Mods, and enable BaseMod plus STS1 Combat Solver. No separate Python, compiler, WSL or source checkout is needed. See [Windows installation](WINDOWS_INSTALL.md) or [Mac installation and release checks](MACOS_INSTALL.md).
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

CI covers Python 3.11 and 3.14 on Windows, macOS and Linux, plus the Apple Silicon portable package. It checks native search/replay, real worker requests and cancellation, diagnostic retention/export, localization and runtime selection. Mac package checks use production Java extraction and the bundled Python in a relocated Unicode path with a minimal PATH. These checks do not prove full live-game parity. CI does not redistribute proprietary game JARs.

## How to use

- **Launch:** After Steam finishes downloading, choose **Play with Mods**. In ModTheSpire, enable BaseMod and STS1 Combat Solver, then start an Ironclad run. Remove old separate `CommunicationMod.jar` and `SteamStateExport.jar` files supplied with previous solver versions.
- **Read the panel:** Switch the panel between English and Chinese on its **Settings** page. In combat, click **Recalculate** to search; compare the displayed routes and potion choices. Search has a finite budget, so a route is a recommendation rather than a proof of optimal play.
- **Manual, in combat:** `Enter` or a click on the route runs one step; **Execute one step** does the same, **Execute turn** runs the rest of the turn, and `F10` runs the route for the whole fight. `F9` or **Stop** halts search and execution and returns to Manual; `F8` folds the panel.
- **Manual, outside combat:** the card lists the candidates with the network's scores and highlights its pick; click another candidate to override it, then press `Enter` or **Confirm this step**.
- **Combat auto and the autopilot:** **Combat auto** (panel button) runs this and later fights automatically, including potion use; **AI autopilot** also decides everything outside combat. **Auto potion swap** is a separate switch for post-combat potion pickup or swaps. See the [potion policy](POTION_POLICY.zh-CN.md).
- **Boss fast finish (Settings, off by default):** A Boss is searched like any fight. When on, the search stops as soon as it finds a potion-free win that costs at most half your HP; this saves time but can lose more HP. The switch starts off for every player, including those with saved settings.
- **Keys:**

  | Key | Action |
  | --- | --- |
  | `Enter` | Manual: run one step (in combat) or confirm the suggested outside action; Combat auto and autopilot: resume this fight's automation |
  | `F10` (rebind in Settings) | Run the solved route for this fight |
  | `F9` | Stop and return to Manual |
  | `F8` | Fold or unfold the panel |
  | `F7` | Save this act's map with the planned route as a PNG on the desktop |
  | `F6` | Export a diagnostic archive (`Shift+F6` finalizes experience parts when recording is on) |
  | `0`-`9`, `Enter` | Pick a route when the panel asks |
- **Report a problem:** Press `F6` to export a local diagnostic archive. Experience recording starts off on every launch; if you enable it, `Shift+F6` finalizes its files. Review files before sharing them. Nothing uploads automatically, and the public build does not record audio; see [Privacy](PRIVACY.md).
