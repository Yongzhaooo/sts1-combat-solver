# Building from source / 从源码构建

Prerequisites: Python 3.11+, CMake 3.19+, a C++17 compiler, JDK 9+ (`java`, `javac`, `jar`), Slay the Spire, ModTheSpire and BaseMod. On macOS install Xcode Command Line Tools; on Windows use Visual Studio C++ Build Tools. Build separately on each OS/CPU architecture with matching Python and compiler architecture; do not copy `.pyd`/`.so` files or a virtual environment between platforms.

需要 Python 3.11+、CMake 3.19+、C++17 编译器、JDK 9+（`java`、`javac`、`jar`），以及游戏、ModTheSpire 和 BaseMod。macOS 使用 Xcode Command Line Tools；Windows 使用 Visual Studio C++ Build Tools。各操作系统／CPU 架构分别构建，Python 与编译器架构须一致；不要跨平台复制 `.pyd`、`.so` 或虚拟环境。

Run from the repository root. macOS/Linux:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install pybind11==3.1.0
.venv/bin/python tools/build_solver_runtime.py
.venv/bin/python tools/check_backend_launcher.py
.venv/bin/python tools/check_backend_protocol.py
.venv/bin/python overlay/build.py --game "/path/to/steamapps/common/SlayTheSpire"
```

Windows (PowerShell):

```powershell
py -3 -m venv .venv
.venv\Scripts\python.exe -m pip install pybind11==3.1.0
.venv\Scripts\python.exe tools/build_solver_runtime.py
.venv\Scripts\python.exe tools/check_backend_launcher.py
.venv\Scripts\python.exe tools/check_backend_protocol.py
.venv\Scripts\python.exe overlay/build.py --game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
```

`--game` is the Steam game directory (on macOS, the directory *containing* `SlayTheSpire.app`). The builder finds Workshop dependencies in the same Steam library; otherwise pass `--modthespire /path/to/ModTheSpire.jar --basemod /path/to/BaseMod.jar`.

The output is `overlay/build/STS1CombatSolver.jar`. Exit the game, disable the Workshop solver and move any other solver JAR out of loaded mod directories, then put the JAR in the game's `mods` directory (on macOS usually `SlayTheSpire.app/Contents/Resources/mods`). The script does not change installed mods or saves.

This is a **local source installation**, not a portable release: the JAR records the absolute paths of this interpreter and backend. Keep the checkout and `.venv` in place, rebuild the JAR after moving either, and do not distribute it. Release packages use `overlay/build.py --bundled` with the runtimes CI builds.

产物为 `overlay/build/STS1CombatSolver.jar`。退出游戏，禁用工坊版求解器并把其他求解器 JAR 移出加载目录，再把它放进游戏的 `mods` 目录（macOS 通常是 `SlayTheSpire.app/Contents/Resources/mods`）。构建脚本不会修改已安装模组或存档。这是**本机源码安装版**：JAR 记录当前解释器和后台的绝对路径，须保留源码目录和 `.venv`，移动后重新构建，不要分发。发布包用 `overlay/build.py --bundled` 和 CI 构建的运行时生成。

Logs and diagnostics: `%LOCALAPPDATA%\STS1CombatSolver` on Windows, `~/Library/Application Support/STS1CombatSolver` on macOS, `${XDG_STATE_HOME:-$HOME/.local/state}/STS1CombatSolver` on Linux (a relative `XDG_STATE_HOME` is ignored).

CI covers Python 3.11 and 3.14 on Windows, macOS and Linux plus the Apple Silicon package: native search and replay, real worker requests and cancellation, diagnostic export, localization and runtime selection. These checks do not prove full live-game parity, and CI does not redistribute proprietary game JARs. Contribution rules are in [CONTRIBUTING.md](CONTRIBUTING.md).
