# 面板与模式开发说明

适用于改求解器面板、游玩模式和按键交互的贡献者，包括用 AI 编程助手开发的人。要做成什么样，先看[产品 spec](product.md) 的 “Play modes and player control” 一节；标了 `(proposed)` 的条目还没最终确定，开工前在 issue 里确认。

## 给 AI 助手的规则

动手前先读完本文和 [CONTRIBUTING](../CONTRIBUTING.md)，然后照下面做：

1. 一个 PR 只做一个功能，例如“战斗全自动加不用药开关”。不顺手重排代码、改格式或改名。
2. 先改纯逻辑并写检查，再接到面板上（见“改动的位置”）。
3. 新增的每条界面文字都用 `I18n.t("中文")` 包起来，同时在 `overlay/resources/localization/en.tsv` 里加一行 `中文<TAB>English`。
4. 不改搜索、估值和药水策略（`overlay/native.cpp`、`overlay/solver_search.py`、`overlay/auto_policy.py`、`overlay/recovery.py`），除非 issue 里说好了要改。
5. 不提交游戏 JAR、反编译代码、游戏图片和字体，也不提交 `overlay/build/`、`.venv/` 这类构建产物。
6. 文本文件用 LF 行尾，Java 8 语法（构建用 `--release 8`）。
7. 下面的检查没过就不要开 PR。哪一步做不到（比如没有游戏），在 PR 里如实写明。

## 代码在哪里

| 位置 | 内容 |
|---|---|
| `overlay/src/sts1solver/SolverMod.java` | 面板绘制、按键和点击、自动执行、与后台通信。界面改动大多在这里 |
| `overlay/src/sts1solver/RunMode.java` | 三种模式（`MANUAL` 人工逐步、`COMBAT_AUTO` 战斗全自动、`PILOT` AI全自动）以及按钮切换规则，纯逻辑 |
| `overlay/src/sts1solver/PanelSize.java`、`SearchStatus.java` | 面板尺寸、搜索超时，纯逻辑 |
| `overlay/src/sts1solver/I18n.java`，`overlay/resources/localization/en.tsv` | 中英文切换；英文表是“中文<TAB>英文” |
| `overlay/backend.py` | 后台入口：接收面板请求、导入战斗、调用搜索、返回路线 |
| `overlay/auto_policy.py` | 战斗全自动时选哪条用药路线（`recommend`） |
| `overlay/test/*.java` | Java 检查，`main` 里用 `require` 断言 |
| `tools/check_*.py` | Python 检查 |

`SolverMod.java` 有 2,200 多行，绘制、状态和通信都在一个类里。已经拆出去的纯逻辑（`RunMode`、`PanelSize` 和 `SolverMod` 里的 `static` 方法）都有 Java 检查。新功能照这个模式写：判断逻辑写成纯函数，放进 `RunMode` 或一个新的小类，配一个检查；`SolverMod` 里只留几行调用。

## 面板状态

`SolverMod` 里和模式有关的字段：

| 字段 | 含义 |
|---|---|
| `outsideMode` | 局外：0 不给建议，1 每步给建议、玩家确认，2 AI全自动 |
| `runAuto` | 整局的战斗都自动打（战斗全自动和 AI全自动） |
| `auto` | 当前这场正在自动执行 |
| `turnOnly` | 只自动执行到本回合结束 |
| `paused` | 自动执行被暂停（F9 或出错） |
| `busy` | 正在等后台回复 |
| `result` | 后台最近一次回复的路线（JSON） |
| `branchPicker` | 正在显示用药路线列表，等玩家输入编号 |
| `branchConfirmed` | 本场路线已经选定 |
| `chosenBranch` | 选定的路线 id，`"no-potion"` 表示不用药 |
| `runAfterBranch` | 玩家按了 F10，选完路线后直接打完整场 |
| `highSpeed`、`streakMode` | 搜索档位：高速、连胜；都为 false 时是标准 |

模式由 `RunMode.of(outsideMode, runAuto)` 推出来。面板上的“战斗全自动”和“AI全自动”按钮分别调用 `toggleCombatMode()` 和 `togglePilotMode()`；`stopAll()`（F9）停下并回到人工逐步。

一场战斗的流程：

1. 进入战斗或局面变化，`send("solve", capture())` 请求后台计算。
2. 后台回复 `status: "ready"`，写进 `result`。
3. 回复里 `needs_confirmation` 为 true 时，进入 `requestBranches()`：
   - 战斗全自动（`runAuto` 且未暂停）调用 `chooseAutomaticBranch()`，按后台的 `auto_recommendation` 选一条；
   - 人工逐步则把 `branchPicker` 设为 true，面板列出路线，玩家输入编号或回车（`branchKeyboard()`）。
4. `chooseBranch(id)` 发出 `send("choose", ...)`，后台按选定路线重算。
5. 执行：`executeStep()` 每次走一步，执行后 `send("advance", ...)` 让后台核对实际结果；`auto` 为 true 时连续执行。

## 面板和后台之间的消息

面板用一行 JSON 发请求，`op` 是 `solve`、`choose`、`advance` 或 `cancel`，`state` 是游戏状态，其中 `game_state.solver_auto_context` 是面板设置（由 `autoContext()` 生成，比如 `high_speed`、`streak_mode`、`feed_mode`）。`choose` 还带 `branch`。

`solve` 和 `choose` 的回复（`backend.py` 中 `Advisor` 生成路线的那段）主要字段：

| 字段 | 含义 |
|---|---|
| `route` | 要执行的动作列表，每项有 `text`，可能带 `label` |
| `won`、`outcome` | 这条路线能不能赢 |
| `loss`、`hp` | 预计掉血、战后生命 |
| `branch` | 当前采用的路线 id |
| `branches` | 全部候选路线：`id`、`name`、`available`、`won`、`loss`，可能有 `potion_gain`（补几瓶药）、`lost_gold` |
| `auto_recommendation` | 战斗全自动会选哪条 |
| `needs_confirmation` | 是否需要玩家选路线 |
| `manual_choice` | 抢劫怪这类必须玩家选的情况 |
| `branch_current` | 路线列表是否对应当前局面 |

搜索中途会发 `status: "progress"` 的回复，带进度和已完成的路线。字段有增减时，面板（`SolverMod`）和后台（`backend.py`）在同一个 PR 里一起改，并更新 `tools/check_backend_protocol.py`。

## 改动的位置（对应产品 spec 里的提议）

- **战斗全自动的“不用药”开关**：设置项照 `registerConfigPanel()` 里的写法加，用 `saveSetting` 保存；在 `chooseAutomaticBranch()` 里，开关打开时只接受 `no-potion` 路线，这条赢不了就调用 `fail(...)` 停下。判断写成 `static` 方法，在 `overlay/test/ModeCheck.java` 里加断言。
- **人工逐步时总显示路线列表**：改面板这一侧（`requestBranches()` 的调用条件，以及 `renderCombatBody()` 里的列表），不要改后台的 `needs_confirmation`，因为战斗全自动也依赖它。
- **限定可用药水**：需要后台新增请求字段，并在 `backend.py` 生成用药路线时使用；属于面板和后台一起改的情况，要加 Python 检查。
- **列表的显示格式**：`renderCombatBody()` 里 `branchPicker` 分支；面板尺寸见 `PanelSize`，改了尺寸要更新 `PanelSizeCheck`。

## 开发环境

按 [BUILDING.md](../BUILDING.md) 准备 Python 3.11+、CMake、C++17 编译器和 JDK，然后在仓库根目录运行（Windows 把 `.venv/bin/python` 换成 `.venv\Scripts\python.exe`）：

```sh
python -m venv .venv
.venv/bin/python -m pip install pybind11==3.1.0
.venv/bin/python tools/build_solver_runtime.py      # 第一次必做，之后只改 Java 时不用重跑
```

只改 Java 和文字时，后台不用重新编译。

## 检查

不需要游戏，CI 也会跑：

```sh
.venv/bin/python tools/check_backend_launcher.py    # 编译启动器，跑 PanelSizeCheck、LanguageCheck
.venv/bin/python tools/check_backend_protocol.py    # 真实后台进程的请求、取消、出错
.venv/bin/python tools/check_executor_tiers.py      # 搜索档位
.venv/bin/python overlay/check_worker_policy.py
```

需要游戏（游戏本体、ModTheSpire、BaseMod），CI 跑不了，提交前在本机跑：

```sh
.venv/bin/python overlay/build.py --game "<游戏目录>"
```

它会编译整个 Mod，并运行 `overlay/test/` 里的全部 Java 检查（`ModeCheck`、`StateKeyCheck`、`OutsidePacketCheck` 等），任何一项失败都会中止。输出 `overlay/build/STS1CombatSolver.jar`。

## 实机测试

1. 退出游戏，在创意工坊里取消订阅或禁用工坊版求解器，把 `overlay/build/STS1CombatSolver.jar` 放进游戏的 `mods` 目录。
2. 用 ModTheSpire 启动，勾选 BaseMod 和本 Mod。
3. 按改动逐项试，并在 PR 里写清楚试了什么：
   - 每种模式各打一场战斗；
   - 切换模式、按 F9 停止，确认不会多走一步，也不会卡住；
   - 中英文各看一次面板，文字没有溢出；
   - 出问题时按 F6 导出诊断，附在 issue 里。
4. 测完把 `mods` 目录里的 JAR 移走，恢复工坊版。

## 提交 PR

1. Fork 本仓库，从 `main` 开分支，名字写功能，例如 `ui/no-potion-switch`。
2. 提交信息用英文，一句话说清改了什么，例如 `feat: Combat auto no-potion switch`。
3. PR 提到 `main`，说明里写：
   - 对应产品 spec 的哪一条，或者哪个 issue；
   - 改了哪些文件，为什么；
   - 跑了哪些检查，结果是什么；
   - 实机测试了什么，附截图更好；
   - 没做到或没测到的地方。
4. CI（`.github/workflows/native-runtime.yml`，三个系统、两个 Python 版本）全部通过后，维护者审阅并在游戏里复测，再合并。
5. 发布由维护者负责：合并进 `main` 不等于发布到创意工坊。
