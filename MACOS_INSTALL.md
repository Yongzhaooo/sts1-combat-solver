# macOS / Mac 安装

For a manual install, download `STS1CombatSolver-macos-v<version>.zip`. Copy its `STS1CombatSolver.jar` and `sts1-solver-macos-arm64.zip` together into the game's `mods` directory. Keep the runtime ZIP compressed. This download is for Apple Silicon (M-series) Macs only; it contains no Windows runtime.

手动安装时下载 `STS1CombatSolver-macos-v<版本>.zip`，把其中的 `STS1CombatSolver.jar` 和 `sts1-solver-macos-arm64.zip` 一起放入游戏 `mods` 目录。后台 ZIP 保持压缩状态。这个包只支持 Apple Silicon（M 系列）Mac，不包含 Windows 后台。

Subscribe to the solver, ModTheSpire and BaseMod on Steam Workshop. Choose **Play with Mods** and enable BaseMod plus STS1 Combat Solver. From v0.9.0 the Workshop package includes only the Apple Silicon backend and Python; Intel Macs are no longer supported, and v0.8.0-beta.3 is the last version for them. The package needs no source checkout, Homebrew or Python installation. macOS 11 or later is required. The game and ModTheSpire still use their own Java runtime.

在 Steam 工坊订阅求解器、ModTheSpire 与 BaseMod，选择“使用模组启动”，勾选 BaseMod 和求解器。自 v0.9.0 起工坊包只带 Apple Silicon 后台及 Python，不再支持 Intel Mac（最后支持的版本是 v0.8.0-beta.3）；无需源码、Homebrew 或另装 Python。要求 macOS 11 或更高版本；游戏与 ModTheSpire 仍使用它们自己的 Java。

Remove any older locally built solver JAR from your loaded mods directories to avoid duplicate mod IDs. If the game's Java runs under Rosetta, the launcher still starts the native Apple Silicon backend; on an Intel Mac it reports that the Mac is unsupported. The first backend start extracts its ZIP into `~/Library/Application Support/STS1CombatSolver/runtimes/<archive-sha256>/`, restoring executable permissions. Updates use a new cache directory and leave diagnostics and experience data intact. Logs and exports are in `~/Library/Application Support/STS1CombatSolver`.

先移除加载目录里旧的本地编译版求解器 JAR，避免重复模组 ID。游戏的 Java 在 Rosetta 下运行时，启动器仍然启动原生 Apple Silicon 后台；在 Intel Mac 上会提示不支持。首次启动会自动解压到上述用户目录并恢复执行权限。更新使用新的缓存目录，诊断和经验数据保留。

Keys: Enter runs one step, F10 runs this fight, F9 stops, F8 folds the panel, F7 saves the act map with the planned route as a PNG on the desktop, F6 exports diagnostics (Shift+F6 finalizes experience parts).

快捷键：回车执行一步，F10 执行这场战斗，F9 停止，F8 收起面板，F7 把本幕地图和计划路线存成桌面 PNG，F6 导出诊断（Shift+F6 封存经验分卷）。

## Release verification / 发布验证

CI builds the Apple Silicon runtime with a pinned, SHA-256-verified CPython archive and checks that an Intel process under Rosetta can start it. It runs four engine CTests, real native search/replay, worker solve/cancel/state-rejection/export/EOF checks, and production Java extraction into a Unicode path with a minimal PATH. Runtime ZIPs contain no game JARs or local developer paths. Windows and Linux source builds also run under Python 3.11 and 3.14.

CI 用固定版本且校验 SHA-256 的 Python 构建 Apple Silicon 后台，并检查 Rosetta 下的 Intel 进程能启动它。验收包括四项引擎 CTest、真实搜索回放、工作进程求解／中断／状态偏离拒绝／诊断导出／退出，以及 Java 正式解包逻辑与中文路径、最小 PATH。运行包不含游戏 JAR 或开发者本机配置。

These checks verify backend deployment and protocol behavior. They do not establish complete game-rule parity or a new end-to-end Steam subscription playthrough on Mac. The original native source adaptation was tested in-game by its contributor; packaged release checks are recorded separately in the release notes.

这些检查验证后台分发和协议行为，不等于完整游戏规则对齐，也不等于已在 Mac 重新完成 Steam 订阅后的整局验收。原生源码适配曾由贡献者进行游戏内测试；发布包验证另见版本说明。
