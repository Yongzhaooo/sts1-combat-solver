# STS1 Combat Solver

[English](README.md)

QQ 交流群：**232459269**。欢迎交流使用经验、反馈问题。

## 致谢与来源

感谢 [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) 提供 C++ 战斗模拟与搜索基础、[destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) 提供改写的 `combat4r` 和 Python 绑定，以及 [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) 提供游戏通信桥。锁定版本和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。

思路方面，[hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) 启发了复制随机状态、调用原版逻辑后恢复现场的预知方法；我们也研究了 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) 的规则镜像和动作核对设计。这两项是《杀戮尖塔 2》的参考项目，本仓库未包含它们的代码。

![铁甲战士梦见指挥尖塔前线](assets/ironclad-hospital-meme.jpg)

## 功能与边界

本项目是《杀戮尖塔 1》战士的本地战斗顾问，也可按需自动执行。它搜索战士及无色牌的战斗路线、比较药水策略，并核对游戏中的实际动作。选牌、路线、商店、休息处和事件默认由玩家决定；开启全自动爬塔后由选路规则和其他局外决策网络决定（见下文）。搜索有预算上限，不保证全局最优或模拟器与游戏完全一致；修改战斗规则的模组可能产生不支持的状态（已知 `SaveStateMod`、`Undo` / `undothespire` 会重写或篡改原生动作队列、发现选牌与状态保存机制，导致随机数与战斗状态脱节报错，不兼容）。

面板支持中英文。可选的种子预知会展示普通可见对局之外的事件、遭遇和变牌结果；其中辉眼模式（默认关闭）控制地图后续房间、事件战斗奖励和涅奥各选项的随机结果，涅奥的四个选项本身照常显示，其他事件预览不受它控制。战后换药与局内用药见[药水分数表](POTION_POLICY.zh-CN.md)。

## 项目目标与进度

- **战士求解器与强化学习（RL）智能体——维护者主线：** 当前发布版支持战士和无色牌。策略研究、训练和评估实验正在进行，v0.4 附带一个早期训练结果，用于下文的全自动爬塔。
- **静默猎手、故障机器人、观者的战斗求解器——欢迎协作开发：** 模拟器里这三个职业的牌和机制大多还没实现。现状和分工见[四职业范围](docs/multi-character.md)，代码与 CI 要求见[参与开发](CONTRIBUTING.md)。联系邮箱：yongzhao.derek@gmail.com。

## 全自动爬塔（早期版本）

> **这是早期训练结果，目前质量还不够好。** 在小数据集上，A20 胜率大约 59%–81%。已知问题：升级偏少、构筑思路不够清晰、容易满血进前几层的精英。请当作试玩功能，不要期待稳定通关。

- **三种模式，每局选择：** 新开一局战士时，求解器会问用哪种。**AI全自动**负责爬塔和战斗；**战斗全自动**只自动打每场战斗，其余决策由你做；**人工逐步**覆盖战斗和局外：求解器和网络给出建议，每一步都等你按回车或点击。AI全自动默认不开启，随时按 `F9` 或点“停止”回到人工逐步。点建议卡片的 × 可收起建议，局外自己操作。
- **谁来决定：** 涅奥、奖励牌、火堆、商店、事件、宝箱、Boss 遗物等局外决策由一个小网络打分选择，候选和分数显示在面板上；战斗沿用现有求解器。网络在 Java 内运行，不需要额外安装。
- **地图路线：** 人工校对后的规则同时决定下一步与到 Boss 的完整路线。一幕重视精英前准备，二幕重视问号和有效商店，第三幕缺绿钥匙时优先经过带火精英。低血降低精英偏好，每次状态变化都可改道；整幕战损预算不再阻止合法路径生成。路线是偏好规划，不是生存预测。
- **删牌限制：** 删牌和变牌只会选诅咒、打击、防御、痛击。
- **牌组变化提示：** 全自动期间获得、强化或删除的牌会在牌组图标下显示 1 秒。

## 安装

- [订阅 Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)，或下载 [v0.4.2 运行包](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.4.2)。
- 面板“设置”页的 − / + 支持 60%–160% 缩放，点击百分比恢复默认。设置会保存，只调整求解器面板，不改变游戏分辨率。
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
- **查看推演：** 在面板的 **设置** 页切换中英文。进入战斗后点 **重新计算**，查看搜索到的路线和药水选择。搜索有预算上限，路线是建议，并非最优性证明。
- **人工逐步，战斗中：** 按 `Enter` 或点击路线执行一步；**执行一步**同效，**执行本回合**执行本回合剩余步骤，`F10` 执行整场战斗的路线。`F9` 或 **停止** 会中止搜索和执行并回到人工逐步；`F8` 收起面板。
- **人工逐步，局外：** 卡片列出候选和网络分数，并标出网络的选择；点另一个候选可改选，然后按 `Enter` 或点 **确认这一步**。
- **战斗全自动与 AI全自动：** 面板上的 **战斗全自动** 会自动打这场和之后的战斗，包括战中用药；**AI全自动** 还会决定所有局外选择。**自动换药**是单独开关，负责战后领取或替换药水。换药规则见[药水分数表](POTION_POLICY.zh-CN.md)。
- **Boss 快速收尾（设置页，默认关闭）：** 关闭时 Boss 和普通战斗一样搜索。开启后，一旦找到不用药且至多损失一半血的胜线就停止搜索，省时间但可能多掉血。这个开关对所有玩家都从关闭开始，包括已保存过设置的玩家。
- **快捷键：**

  | 键 | 作用 |
  | --- | --- |
  | `Enter` | 人工逐步：战斗中执行一步，局外确认建议的动作；战斗全自动和 AI全自动：恢复这场战斗的自动执行 |
  | `F10`（可在设置页改键） | 执行这场战斗已算出的路线 |
  | `F9` | 停止，回到人工逐步 |
  | `F8` | 收起或展开面板 |
  | `F7` | 把本幕地图和计划路线存成桌面上的 PNG |
  | `F6` | 导出诊断包（开启经验录制后，`Shift+F6` 封存经验分卷） |
  | `0`-`9`、`Enter` | 面板要求选路线时选编号 |
- **反馈问题：** `F6` 导出本地诊断包。经验录制每次启动默认关闭；主动开启后可用 `Shift+F6` 封存文件。分享前请检查内容。程序不会自动上传，公开版不录音；详见[隐私说明](PRIVACY.zh-CN.md)。
