# STS1 Combat Solver

[English](README.md)

《杀戮尖塔 1》战士的本地战斗求解器：搜索整场战斗的出牌和用药路线，可以逐步执行、自动打每场战斗，也可以全自动爬塔。觉得有用的话，请给仓库点个 Star。

QQ 交流群：**232459269**。欢迎交流使用经验、反馈问题。

![铁甲战士梦见指挥尖塔前线](assets/ironclad-hospital-meme.jpg)

## 安装

- [订阅 Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)，同时订阅 ModTheSpire 和 BaseMod。选择“使用模组启动”，勾选 BaseMod 和求解器，开始战士对局。也可以从 [Releases](https://github.com/Yongzhaooo/sts1-combat-solver/releases) 下载安装包。
- 支持 Windows 10/11 x64 和 Apple Silicon（M 系列）Mac 上的 macOS 11+，不支持 Intel Mac。无需另装 Python。详见 [Windows 安装说明](WINDOWS_INSTALL.md)和 [Mac 安装说明](MACOS_INSTALL.md)。
- 从源码构建见 [BUILDING.md](BUILDING.md)。

## 使用

- **三种模式，每局开始时选择：** **人工逐步**：战斗中按回车或点击路线执行一步，局外确认求解器的建议；**战斗全自动**：自动打每场战斗（含用药），局外由你决定；**AI全自动**：爬塔和战斗都交给求解器。`F9` 或“停止”随时回到人工逐步。
- **搜索量：** 默认自动档，按幕和房间分配。老电脑可在面板切到**高速模式**：每场战斗只用 1/20 搜索量，更快，但胜率和省血会下降。冲连胜可切到**连胜模式**：前三幕不降搜索量，更慢。
- **其他开关：** 自动换药（战后领取和替换药水，规则见[药水分数表](POTION_POLICY.zh-CN.md)）；设置页的 Boss 快速收尾（默认关闭：找到不用药且至多损失一半血的胜线就停止搜索）、缩放、语言和辉眼；Mods → Config 里的狂宴模式、测试模式和路线标注。电脑卡顿时，可以调低 ModTheSpire 配置 `STS1CombatSolver/config.properties` 里的 `searchWorkers`（默认 0，即自动）。
- **快捷键：**

  | 键 | 作用 |
  | --- | --- |
  | `Enter` | 人工逐步：执行一步或确认局外建议；自动模式下恢复这场战斗的自动执行 |
  | `F10`（可改键） | 执行这场战斗已算出的路线 |
  | `F9` | 停止，回到人工逐步 |
  | `F8` | 收起或展开面板 |
  | `F7` | 把本幕地图和计划路线存成桌面 PNG |
  | `F6` | 导出诊断包（开启经验录制后，`Shift+F6` 封存经验分卷） |

- **反馈问题：** 按 `F6`（或面板标题栏的“导出”）导出诊断包，发到 QQ 群或 GitHub Issues。数据只存在本机（Windows `%LOCALAPPDATA%\STS1CombatSolver`，Mac `~/Library/Application Support/STS1CombatSolver`），不会自动上传，分享前请先看[隐私说明](PRIVACY.zh-CN.md)。

## 全自动爬塔（早期版本）

局外决策（涅奥、奖励牌、火堆、商店、事件、宝箱、Boss 遗物）由一个小网络打分，路线由人工校对的规则决定，战斗交给求解器。训练环境 2,442 局 A20 三钥匙心脏胜率 73.1%（换用 v0.9 更快的搜索后，1,014 局胜率 71.0%）。已知问题：升级偏少、构筑思路不够清晰、容易满血进前几层的精英。删牌和变牌只选诅咒、打击、防御和痛击。

## 让 AI agent 来玩

把下面这句话发给你的 agent（Claude Code、Codex 等能在本机读写文件、运行命令的 agent）：

> 阅读 https://github.com/Yongzhaooo/sts1-combat-solver/blob/agent-play/AGENT_PLAY.md ，按里面的步骤检查并配置我的杀戮尖塔模组（处理好不兼容的 mod），然后用求解器帮我自动爬塔。

agent 负责局外决策，求解器负责战斗。需要 v0.9.0 或更新版本。

## 局限

- 只支持战士和无色牌。搜索量有上限，不保证最优；七月流火（猫火）的实测表明，在一些特殊解上求解器仍不如顶尖玩家，例如燃烧契约调牌序、异蛇之油，以及有时算不出的小循环。
- 修改战斗规则或随机数的模组不兼容，例如 `SaveStateMod`、Undo（`undothespire`）；求解器检测到这类模组时会在设置页提示。
- 种子预知会显示正常游玩看不到的事件、遭遇和变牌结果；辉眼（默认关闭）另外控制地图后续房间、事件战斗奖励和涅奥各选项的随机结果。

## 参与开发

静默猎手、故障机器人和观者的求解器欢迎协作开发：现状见[四职业范围](docs/multi-character.md)，代码与 CI 要求见[参与开发](CONTRIBUTING.md)，组件结构见[架构](docs/architecture.md)。联系邮箱：yongzhao.derek@gmail.com。

## 致谢

特别感谢七月流火（猫火）长期测试与反馈。七月流火（猫火）用求解器手操近期打出约 80 连胜（等效 SL），即将达成百连胜。

感谢 [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) 提供 C++ 战斗模拟与搜索基础，[destire-mio/sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent) 提供改写的 `combat4r` 和 Python 绑定，[ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) 提供游戏通信桥；锁定版本和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。[hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) 启发了随机预知的做法，[Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) 的规则镜像和动作核对设计也给了参考；这两项是《杀戮尖塔 2》的项目，本仓库未包含它们的代码。
