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

目标是做出覆盖《杀戮尖塔 1》**四个职业的战斗求解器**，并开发**强化学习（RL）智能体**。RL 研究和训练实验已经在进行。当前发布版只支持战士及无色牌；猎手、机器人和观者仍需补齐模拟器。四职业求解与 RL 是正在推进的目标，尚不是此版本已完成的功能。

## 安装

- [订阅 Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)，或下载 [v0.2.1 Windows 包](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.2.1)。
- 仅支持 Windows 10/11 x64。ModTheSpire 和 BaseMod 需另行安装，详见 [Windows 安装说明](WINDOWS_INSTALL.md)。发布包自带原生后台，玩家无需 WSL、另装 Python 或克隆源码。
- 开发者可参考[原生构建说明](WINDOWS_PORTABLE.md)。Linux、macOS 和 Steam Deck 暂无运行包。

## 快捷键与隐私

`F10` / `Enter` 切换自动战斗，`F9` 停止搜索和执行，`F8` 收起面板。`F6` 导出本地诊断报告。经验录制每次启动默认关闭；主动开启后可用 `Shift+F6` 封存。程序不会自动上传，公开版不录音。分享文件前请自行检查，详见[隐私说明](PRIVACY.zh-CN.md)。
