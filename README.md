# STS1 Combat Solver

[简体中文](README.zh-CN.md)

A local combat advisor and optional combat executor for the Ironclad in Slay the Spire 1. You build your own deck and select your map route; the solver searches the current combat, evaluates alternative potion lines, and verifies executed actions against the game state.

The native Windows package includes the full backend and needs no WSL, separate Python installation or source checkout. See [Windows installation](WINDOWS_INSTALL.md). This repository provides developer sources; the WSL development build remains documented below, and the [native build guide](WINDOWS_PORTABLE.md) covers Windows packaging. No game files, model weights or private run histories are redistributed.

## Features and limits

- **Bilingual interface**: Supports English and Chinese. Click `Language` in the lower-left corner to toggle; the preference persists across restarts. Card, relic, potion, and enemy names match the active game language. Set the game to English to display English entity names.
- **Combat planning**: Provides finite-budget search, potion route comparison, single-step execution, single-turn execution, and full combat automation. The search outputs a discovered route without guaranteeing global optimality.
- **Scope of automation**: Supports optional potion reward swaps and continuous combat handling. Card reward selections, path choices, shops, rest sites, and event decisions remain under direct player control.
- **Potion policy**: The [potion score table and use rules](POTION_POLICY.zh-CN.md) document reward swaps and combat decisions (Chinese).
- **Seeded foresight**: Offers optional outcome previews for Neow blessings, map encounters, events, and card transforms. This functionality exposes state information beyond standard visible-information play.
- **Keybindings**: 
  - `F9`: Stop current search and automation.
  - `F8`: Collapse or expand the overlay panel.
  - `F10`: Toggle combat automation.
  - `F6`: Export a local diagnostic bug report.
  - `Shift+F6`: Finalize active experience parts and open their storage directory.

Combat support is currently limited to the Ironclad. Mods that alter core game mechanics can produce unsupported engine states. A simulated replay provides useful verification evidence but does not establish full equivalence with the native game engine. Evaluation of future resource trajectories and potion reward preferences relies in part on hand-tuned heuristics.

## Build from source (WSL development mode)

Prerequisites: A JDK providing `javac` and `jar`, PowerShell 7, WSL Linux, CMake, a C++17 compiler, and Python 3. Install Python's `pybind11` inside the backend virtual environment. Subscribe to and install ModTheSpire and BaseMod via Steam.

From this checkout inside WSL:

```bash
python3 -m venv candidates/sts-ironclad-agent/.venv
candidates/sts-ironclad-agent/.venv/bin/python -m pip install pybind11
PYTHONUTF8=1 PYTHONIOENCODING=utf-8 candidates/sts-ironclad-agent/.venv/bin/python tools/build_solver_runtime.py
```

The build step compiles the simulation engine alongside Python bindings, executes the core C++ regression tests, writes an engine identity manifest, and compiles the solver's native extension. No teacher policy weights are required.

From PowerShell in this checkout:

```powershell
pwsh -NoProfile -File overlay/build.ps1 -Game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
pwsh -NoProfile -File overlay/check-language.ps1
```

Adjust the `-Game` path to match your actual game installation directory. Copy `overlay/build/STS1CombatSolver.jar` into the game's `mods/` directory. Communication and state export are embedded in this JAR:
- `STS1CombatSolver.jar`

Launch the game through ModTheSpire and manually enable BaseMod and STS1 Combat Solver. ModTheSpire does not automatically select dependencies.

The generated JAR embeds the absolute filesystem path of this checkout's backend. Keep the repository in place, or rebuild the JAR if the checkout is moved. Because Steam Workshop installs files to a distinct directory structure, this build cannot serve as a subscription-ready binary distribution. See [Workshop preparation](WORKSHOP_PUBLISHING.md) for packaging context.

## Debugging and optional training contributions

Pressing `F6` exports diagnostic data from one recent encounter to `%LOCALAPPDATA%\STS1CombatSolver\bug-reports` and opens the destination folder. The local debug database retains up to 300 events across the three most recent runs. Each debug export contains at most 100 events from the recent battle, is capped at 1 MiB compressed, and drops older events when necessary to respect this limit. Export archives include game and mod version strings, game state representations, run seeds, attempted actions, and logged errors. Known account fields and local path prefixes are filtered; review the plaintext JSON before sharing. Audio data, raw save files, and the complete SQLite database are excluded. Submitting a bug report does not grant permission to use the data for model training.

Experience recording is disabled by default each time the game launches. Click `Experience: Off` in the header bar to opt in for the current session. When active, it records full exported game states, deck lists, card identities, draw/discard/exhaust piles, monster attributes, status powers, relic counters, selection contexts, run recovery metadata, relative timing, and solver action submissions alongside planned routes. Internal RNG and draw pool states are flagged as privileged metadata; they must not be fed directly to a visible-information policy. Observed state transitions during manual play represent observations rather than verified player click labels, and full replay fidelity is not guaranteed.

Experience data uses lossless ZIP/JSONL compression. Recording rotates into a new part once uncompressed event data reaches approximately 4 MiB, rather than discarding earlier records or truncating deck and action lists. A single large event can occupy an entire part; events exceeding 8 MiB halt recording with an error instead of being truncated. There is no cumulative run size cap. Completed parts remain under `%LOCALAPPDATA%\STS1CombatSolver\experience` until manually deleted. Pressing `Shift+F6` finalizes the active part and opens the session folder; provide all relevant parts together to preserve recording sequence. Switching recording off finalizes the active part; the toggle resets to Off on the next game launch.

The public release build does not record microphone input or maintain a private research decision journal. The mod contains no automated upload mechanisms or background telemetry. Users must review and manually upload bug reports, or separately choose to contribute experience data for AI evaluation or training. Review the [privacy and contribution notice](PRIVACY.md) before sharing files. Do not post personal information, audio recordings, complete save files, or private debug logs to public issue trackers.

## Source and license

The Java modules and native backend binaries were built from the exported source on 2026-10-06. The three core C++ checks and an isolated-process advisor import check passed. An isolated original-game test with BaseMod, CommunicationMod, SteamStateExport and the solver (plus a test driver) completed one Ironclad fight at normal speed, with no optional gameplay mods. English rendering, language persistence across restarts, debug export and complete recorded event ordering were checked. This is a smoke test, not full-run or universal mod compatibility coverage. The underlying simulation core continues to emit shift-count-overflow compiler warnings in `Player.h`.

The overlay code is original to this project. Its game state communication bridge is based on [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod). Native simulation and tree search originate from a pinned snapshot of [sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent), which derives from [sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed). See [third-party notices](THIRD_PARTY_NOTICES.md). Original code is released under the MIT license; upstream licensing terms are retained in their respective directories.

## Roadmap

**Long-Term Goal**
The long-term project goal is to develop a reinforcement-learning (RL) agent for *Slay the Spire*. Community discussion, bug reports, gameplay feedback, and contributions are welcome. Any collection of training data is strictly voluntary and governed by a separate privacy notice.

**Simulator & Character Support**
The current combat solver supports only Ironclad and colorless cards. Support for The Silent, The Defect, and The Watcher requires rebuilding the underlying simulator. These are active engineering goals rather than existing features, and they do not represent a committed timeline.

**Distribution**
The Windows x64 bundle includes the native backend and runs without WSL, a separate Python installation, or a source checkout. Linux, macOS, and Steam Deck builds are not available yet.


See [Windows installation](WINDOWS_INSTALL.md) and the [release verification record](WINDOWS_PORTABLE.md) for current native distribution status.
