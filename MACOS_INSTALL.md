# macOS / Mac 安装

Subscribe to the solver, ModTheSpire and BaseMod on Steam Workshop. Choose **Play with Mods** and enable BaseMod plus STS1 Combat Solver. The v0.3.0 Workshop package includes both Apple Silicon and Intel backends and Python; no source checkout, Homebrew or Python installation is needed. macOS 11 or later is required. The game and ModTheSpire still use their own Java runtime.

在 Steam 工坊订阅求解器、ModTheSpire 与 BaseMod，选择“使用模组启动”，勾选 BaseMod 和求解器。v0.3.0 工坊包自带 Apple Silicon、Intel 两套后台及 Python，无需源码、Homebrew 或另装 Python。要求 macOS 11 或更高版本；游戏与 ModTheSpire 仍使用它们自己的 Java。

Remove any older locally built solver JAR from your loaded mods directories to avoid duplicate mod IDs. The launcher selects the runtime matching the JVM architecture; an Intel JVM under Rosetta uses the Intel runtime. The first backend start extracts its ZIP into `~/Library/Application Support/STS1CombatSolver/runtimes/<archive-sha256>/`, restoring executable permissions. Updates use a new cache directory and leave diagnostics and experience data intact. Logs and exports are in `~/Library/Application Support/STS1CombatSolver`.

先移除加载目录里旧的本地编译版求解器 JAR，避免重复模组 ID。启动器按 JVM 架构选包；Rosetta 下的 Intel JVM 使用 Intel 后台。首次启动会自动解压到上述用户目录并恢复执行权限。更新使用新的缓存目录，诊断和经验数据保留。

## Release verification / 发布验证

CI builds the two architectures independently using pinned, SHA-256-verified CPython archives. It runs four engine CTests, real native search/replay, worker solve/cancel/state-rejection/export/EOF checks, and production Java extraction into a Unicode path with a minimal PATH. Runtime ZIPs contain no game JARs or local developer paths. Windows and Linux source builds also run under Python 3.11 and 3.14.

CI 分别构建两种架构，使用固定版本且校验 SHA-256 的 Python。验收包括四项引擎 CTest、真实搜索回放、工作进程求解／中断／状态偏离拒绝／诊断导出／退出，以及 Java 正式解包逻辑与中文路径、最小 PATH。运行包不含游戏 JAR 或开发者本机配置。

These checks verify backend deployment and protocol behavior. They do not establish complete game-rule parity or a new end-to-end Steam subscription playthrough on Mac. The original native source adaptation was tested in-game by its contributor; packaged release checks are recorded separately in the release notes.

这些检查验证后台分发和协议行为，不等于完整游戏规则对齐，也不等于已在 Mac 重新完成 Steam 订阅后的整局验收。原生源码适配曾由贡献者进行游戏内测试；发布包验证另见版本说明。
