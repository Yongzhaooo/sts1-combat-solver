# STS1 Combat Solver

[English](README.md)

针对《杀戮尖塔 1》（Slay the Spire 1）铁甲战士（Ironclad）的本地战斗辅助与可选战斗执行工具。玩家自行构筑牌组并选择地图路线；求解器负责搜索当前战斗路线、对比药水使用策略，并根据游戏状态校验各项执行动作。

Windows 原生分发包内带完整后台，无需 WSL、另装 Python 或源码。安装见 [Windows 安装说明](WINDOWS_INSTALL.md)。本仓库提供开发者源码，下面保留 WSL 开发构建流程；Windows 原生构建见 [构建说明](WINDOWS_PORTABLE.md)。包内不包含游戏本体、模型权重或私人对局记录。

## 功能特性与已知边界

- **双语界面**：提供中英文界面支持。点击左下角 `Language` 即可切换，设置在重启后保持生效。卡牌、遗物、药水与敌人名称与游戏本体当前语言一致。如需显示英文名称，请将游戏语言设为英文。
- **战斗计算**：支持有限算力预算下的战斗搜索、药水路线对比、单步执行、单回合执行以及全自动战斗。求解器仅输出当前搜索到的行动路线，不证明该路线具备全局最优性。
- **自动化范围**：支持可选的战斗后药水替换与连续战斗。卡牌奖励挑选、路线分支、商店消费、休整处以及各类事件选项仍由玩家手动决策。
- **药水策略**：战后领取和换药的完整分数、组合加分及战斗内用药原则见[药水评分与使用规则](POTION_POLICY.zh-CN.md)。
- **种子预知**：提供对涅奥奖励、地图遇敌、随机事件以及卡牌转变的可选预测。该功能所展示的信息超出了普通可视信息对局的范畴。
- **快捷键**：
  - `F9`：停止当前搜索与自动执行。
  - `F8`：折叠或展开辅助面板。
  - `F10`：切换战斗自动化开关。
  - `F6`：导出本地问题诊断报告。
  - `Shift+F6`：封包当前经验分卷并打开所在目录。

战斗模块目前仅适配铁甲战士。引入修改核心游戏规则的 Mod 可能会导致求解器进入不支持的状态。基于模拟引擎复现的路线可作为有效的参考依据，但不代表与原版游戏逻辑完全等价。针对后续资源价值评估与药水奖励偏好的计算包含部分人工编写的启发式规则。

## 源码构建（WSL 开发模式）

环境要求：安装包含 `javac` 与 `jar` 的 JDK、PowerShell 7、WSL Linux、CMake、支持 C++17 的编译器以及 Python 3。在后端虚拟环境中安装 Python `pybind11` 库。通过 Steam 订阅并安装 ModTheSpire 与 BaseMod。

在 WSL 环境下于项目根目录执行：

```bash
python3 -m venv candidates/sts-ironclad-agent/.venv
candidates/sts-ironclad-agent/.venv/bin/python -m pip install pybind11
PYTHONUTF8=1 PYTHONIOENCODING=utf-8 candidates/sts-ironclad-agent/.venv/bin/python tools/build_solver_runtime.py
```

该流程将编译模拟器核心及其 Python 绑定、执行核心 C++ 校验用例、生成引擎标识清单，并编译求解器专用的原生扩展模块。构建过程不需要教师策略模型权重。

在 PowerShell 中于项目根目录执行：

```powershell
pwsh -NoProfile -File overlay/build.ps1 -Game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
pwsh -NoProfile -File overlay/check-language.ps1
```

请将 `-Game` 参数替换为您实际的游戏安装路径。将 `overlay/build/STS1CombatSolver.jar` 复制到游戏的 `mods/` 目录中。通信桥和状态导出已内置：
- `STS1CombatSolver.jar`

通过 ModTheSpire 启动游戏，并手动勾选启用 BaseMod 与 STS1 Combat Solver。ModTheSpire 不会自动勾选依赖项。

编译出的 JAR 文件内部固化了当前源码仓库后端的绝对路径。请保持此工作区路径不变；如移动仓库目录，须重新编译 JAR 文件。由于 Steam 创意工坊采用不同的安装目录机制，当前构建产物不能直接作为订阅即用的二进制发布包。相关背景请参考 [创意工坊准备说明](WORKSHOP_PUBLISHING.md)。

## 问题排查与可选训练数据贡献

按下 `F6` 会将最近一场战斗的数据导出至 `%LOCALAPPDATA%\STS1CombatSolver\bug-reports` 并自动打开该目录。本地调试数据库最多保留最近 3 局游戏中的 300 条事件。单次导出的诊断包最多包含最近战斗中的 100 条事件，压缩包体积上限为 1 MiB；为控制体积，可能会省略更早的事件记录。导出内容包括游戏与 Mod 版本、游戏状态快照、种子、动作记录及诊断错误日志。已知账号字段与本地路径信息已被过滤；上传前请自行检查明文 JSON。导出包中不包含游戏音频、原始存档文件或完整数据库文件。提交诊断报告不构成授权将其用于模型训练。

经验记录功能在每次游戏启动时默认处于关闭状态。点击顶部栏的 `Experience: Off` 可在当前会话中主动开启。开启后将记录完整导出的游戏状态、卡组与卡牌标识、抽牌堆/弃牌堆/消耗堆、怪物属性、能力状态、遗物计数器、交互选择上下文、对局恢复字段、相对时间戳以及求解器动作提交与推演计划。内部 RNG 与卡池等隐藏状态会被标记为特权数据（privileged）；此类数据不得直接输入给仅依赖可视信息的策略模型。观察到的玩家操作状态变化仅作为环境观测数据记录，并非确认后的真实点击标签，且不保证能够进行完整重放。

经验记录采用无损 ZIP/JSONL 压缩。当未压缩事件数据累计达到约 4 MiB 时会自动轮转为新分卷，而不是丢弃早期记录或截断卡组与动作列表。单个超大事件可独立占用一个分卷；若单条事件数据超过 8 MiB，记录器将报错中止而不是强行截断。单次完整运行没有总容量上限。已完成的分卷将保存在 `%LOCALAPPDATA%\STS1CombatSolver\experience` 下，直至用户手动清理。按下 `Shift+F6` 可关闭当前分卷并打开会话目录；分享数据时请打包提交相关分卷以保持序列完整。关闭记录开关会封存当前分卷；下次启动游戏时仍恢复为关闭状态。

默认公开构建版本不会录制麦克风音频，也不会生成私有研究决策日志。Mod 本体不包含任何自动上传功能或遥测机制。用户须手动审查并提交诊断报告，或单独确认参与 AI 评估与训练数据共享。分享前请查阅 [隐私与贡献说明](PRIVACY.zh-CN.md)。请勿在公开 Issue 中附带个人身份信息、对局录音、完整游戏存档或未脱敏日志。

## 源码与许可证

Java 模块与原生后端二进制于 2026-10-06 基于导出源码构建完成。三项核心 C++ 校验用例与新进程环境下的 advisor 导入校验均已通过。隔离的原版游戏测试仅加载 BaseMod、CommunicationMod、SteamStateExport 与求解器，另加测试驱动；未加载其他游戏模组，在正常速度下完成了一场战士战斗。已检查英文显示、重启后语言设置保留、诊断导出与经验事件顺序完整性。这是基础冒烟测试，不代表完整通关或任意模组组合均已验证。底层核心代码在编译 `Player.h` 时仍会输出位移溢出（shift-count-overflow）相关的编译器警告。

Overlay 界面与桥接逻辑为本项目原创代码。状态通信桥接依赖 [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod)。原生模拟与搜索模块源自锁版本的 [sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) 快照，该上游分支基于 [sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) 开发。具体参见 [第三方声明](THIRD_PARTY_NOTICES.md)。原创代码采用 MIT 许可证；上游代码沿用各自目录下的许可条款。

## 开发路线

**长期目标**
本项目的长期目标是开发面向《杀戮尖塔》（Slay the Spire）的强化学习（RL）智能体。欢迎社区参与讨论、提交缺陷报告、反馈体验及贡献代码。训练数据的提供遵循自愿原则，并受独立的隐私声明管辖。

**模拟器与角色支持现状**
当前战斗求解器仅支持铁甲战士（Ironclad）及无色卡牌。对静默猎手（Silent）、故障机器人（Defect）和观者（Watcher）的支持需要重构底层模拟器。上述内容均为开发规划，不属于已有功能，亦不承诺具体上线时间表。

**分发目标**
下一阶段的分发目标是提供独立的 Windows 创意工坊安装包，运行时无需 WSL、无需另行安装 Python，也无需克隆源码。原生 Windows 适配目前正在开发中，尚未公开发布，亦未经过订阅用户测试。



原生安装与验证进展见 [Windows 安装说明](WINDOWS_INSTALL.md) 和 [发布记录](WINDOWS_PORTABLE.md)。
