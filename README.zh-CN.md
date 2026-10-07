# STS1 Combat Solver

[English](README.md)

## 致谢与来源

感谢 [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) 提供 C++ 战斗模拟与搜索基础、[destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) 提供改写的 `combat4r` 和 Python 绑定，以及 [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) 提供游戏通信桥。锁定版本和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。

思路方面，[hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) 启发了复制随机状态、调用原版逻辑后恢复现场的预知方法；我们也研究了 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) 的规则镜像和动作核对设计。这两项是《杀戮尖塔 2》的参考项目，本仓库未包含它们的代码。

![铁甲战士梦见指挥尖塔前线](assets/ironclad-hospital-meme.jpg)

## 功能与边界

本项目是《杀戮尖塔 1》战士的本地战斗顾问，也可按需自动执行。它搜索战士及无色牌的战斗路线、比较药水策略，并核对游戏中的实际动作。选牌、路线、商店、休息处和事件由玩家决定。搜索有预算上限，不保证全局最优或模拟器与游戏完全一致；修改战斗规则的模组可能产生不支持的状态。

面板支持中英文。可选的种子预知会展示普通可见对局之外的事件、遭遇和变牌结果。战后换药与局内用药见[药水分数表](POTION_POLICY.zh-CN.md)。

## 项目目标与进度

- **四职业战斗求解器——正在推进：** 当前发布版支持战士和无色牌；猎手、机器人、观者还需要补齐模拟器。
- **强化学习（RL）智能体——正在推进：** 策略研究、训练和评估实验已经在进行；此版本尚未提供 RL 智能体。

## 安装

- [订阅 Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)，或下载 [v0.3.0 运行包](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.3.0)。
- 工坊包支持 Windows 10/11 x64 和 macOS 11+（Apple Silicon、Intel）。另行订阅 ModTheSpire、BaseMod，选择“使用模组启动”并勾选 BaseMod 与求解器即可，无需另装 Python、编译器、WSL 或克隆源码。详见 [Windows 安装说明](WINDOWS_INSTALL.md)及 [Mac 安装与发布验证](MACOS_INSTALL.md)。
- Windows、macOS、Linux 的本地源码构建见下文。Linux／Steam Deck 暂无随工坊分发的运行时。

### 三平台原生源码构建

需要 Python 3.11+、CMake 3.19+、C++17 编译器、JDK 9+（`java`、`javac`、`jar`），以及游戏、ModTheSpire 和 BaseMod。macOS 使用 Xcode Command Line Tools；Windows 使用 Visual Studio C++ Build Tools。各操作系统／CPU 架构分别构建，Python 与编译器架构须一致；不要跨平台复制 `.pyd`、`.so` 或虚拟环境。

在仓库根目录执行。macOS/Linux：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install pybind11==3.1.0
.venv/bin/python tools/build_solver_runtime.py
.venv/bin/python tools/check_backend_launcher.py
.venv/bin/python tools/check_backend_protocol.py
.venv/bin/python overlay/build.py --game "/path/to/steamapps/common/SlayTheSpire"
```

Windows（PowerShell）：

```powershell
py -3 -m venv .venv
.venv\Scripts\python.exe -m pip install pybind11==3.1.0
.venv\Scripts\python.exe tools/build_solver_runtime.py
.venv\Scripts\python.exe tools/check_backend_launcher.py
.venv\Scripts\python.exe tools/check_backend_protocol.py
.venv\Scripts\python.exe overlay/build.py --game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
```

`--game` 指向 Steam 游戏目录；macOS 指向包含 `SlayTheSpire.app` 的目录。构建器自动查找同一 Steam 库中的工坊依赖；不同库或手动安装时，可显式传入 `--modthespire /path/to/ModTheSpire.jar --basemod /path/to/BaseMod.jar`。

产物为 `overlay/build/STS1CombatSolver.jar`。完全退出游戏后再放入游戏的 `mods` 目录（macOS 启动目录通常是 `SlayTheSpire.app/Contents/Resources`）。先禁用工坊版求解器，将旧求解器 JAR 移出所有加载目录，避免重复加载；保留 BaseMod/ModTheSpire。构建脚本不会修改已安装模组或存档。

这是**本机源码安装版，不是可分发整包**：JAR 记录当前解释器和后台的绝对路径，必须保留源码目录和 `.venv`，移动后重新构建 JAR。不要分发此含本机路径的 JAR。旧 Windows 随包／WSL 流程保留；`overlay/build.py --bundled-windows --game ...` 可生成交给原 Windows 打包器使用的 JAR。

日志与诊断目录：Windows 为 `%LOCALAPPDATA%\STS1CombatSolver`；macOS 为 `~/Library/Application Support/STS1CombatSolver`；Linux 为 `${XDG_STATE_HOME:-$HOME/.local/state}/STS1CombatSolver`（相对的 `XDG_STATE_HOME` 会被忽略）。旧 WSL 模式继续使用原配置目录。

CI 覆盖 Windows、macOS、Linux 的 Python 3.11／3.14，以及独立构建的 Apple Silicon、Intel 便携包。检查包括原生搜索／回放、真实求解请求、中断、诊断保留／导出、语言和运行时选择。Mac 包用正式 Java 解包逻辑，在中文路径和最小 PATH 下启动随包 Python。这些检查不代表原版机制完全对齐；CI 不分发专有游戏 JAR。

## 使用教程

- **启动游戏：** 等 Steam 下载完成后选择“使用模组启动”，在 ModTheSpire 中勾选 BaseMod 和 STS1 Combat Solver，再开始战士对局。若装过旧版求解器，删除当时附带的独立 `CommunicationMod.jar`、`SteamStateExport.jar`。
- **查看推演：** 点击面板的 **Language** 切换中英文。进入战斗后点 **重新计算**，查看搜索到的路线和药水选择。搜索有预算上限，路线是建议，并非最优性证明。
- **执行路线：** 点击已完成且核对通过的路线可直接执行；也可对当前结果选择 **执行一步**、**执行本回合** 或 **自动战斗**。`F10` / `Enter` 可切换战斗自动化；`F9` 或 **停止** 会中止搜索和执行；`F8` 收起面板。
- **选择自动化范围：** **自动换药**负责战后领取或替换药水；**自动用药＋跨场战斗**负责战中用药和后续战斗，两者可分别开关。奖励牌、路线、商店、休息处和事件仍由你选择。换药规则见[药水分数表](POTION_POLICY.zh-CN.md)。
- **反馈问题：** `F6` 导出本地诊断包。经验录制每次启动默认关闭；主动开启后可用 `Shift+F6` 封存文件。分享前请检查内容。程序不会自动上传，公开版不录音；详见[隐私说明](PRIVACY.zh-CN.md)。
