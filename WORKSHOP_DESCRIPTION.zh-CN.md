# STS1 Combat Solver — Ironclad / 战士求解器

《杀戮尖塔 1》的本地战斗求解器。你负责构筑和选路，它负责搜索当前战斗、比较药水路线，并按你的选择单步执行、执行一回合或自动战斗。面板支持中文和英语；卡牌、遗物和敌人的名称跟随游戏语言。

## 安装

当前发布包面向 Windows 10/11 x64，包含原生后台，无需 WSL、Python、编译器或源码。工坊条目尚未发布。

订阅此条目、[ModTheSpire](https://steamcommunity.com/sharedfiles/filedetails/?id=1605060445) 和 [BaseMod](https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019)，等待下载完成。从 Steam 选择“使用模组启动”，在 ModTheSpire 中勾选 BaseMod 和 STS1 Combat Solver，再启动游戏。通信桥和状态导出已合并到同一个求解器 JAR。升级时移除旧版求解器附带的独立 CommunicationMod.jar 和 SteamStateExport.jar。

不需要 SuperFastMode、SaveStateMod 或其它非基础模组。修改战斗规则的模组可能产生不支持的状态。Linux、macOS 和 Steam Deck 暂未提供运行包。

## 使用与边界

当前只支持战士牌和无色牌。求解使用有限预算，不保证全局最优，也不能保证底层模拟与游戏完全一致。卡牌奖励、路线、商店、休息和事件仍由你决定。辉眼功能会读取种子和随机状态，显示普通可见信息之外的结果。

- 点击面板的 Language 切换语言，重启后保留设置。
- F10 / Enter 切换战斗自动化；F9 停止搜索和执行；F8 收起或展开面板。
- F6 导出本地诊断包，分享前请检查内容。
- 经验录制每次启动默认关闭。手动开启后，Shift+F6 封存并打开完整、无损压缩的经验分卷。

日志和经验保存在 `%LOCALAPPDATA%\STS1CombatSolver`，工坊更新不会覆盖它们。程序不会自动上传，公开发布版不录音。诊断报告不授予 AI 训练许可；经验文件只在你另行明确同意后才能用于训练。训练数据接收渠道尚未开放，请先保存在本地，勿直接把含个人信息的文件贴到工坊讨论区。完整说明见包内 PRIVACY.zh-CN.md。

## 后续开发

长期目标是开发尖塔 RL 智能体，欢迎交流、反馈和代码贡献。猎手、机器人和观者需要重做底层模拟器；它们和 RL 都是开发目标，没有承诺上线日期。
