# Windows installation / Windows 安装

For Windows 10/11 x64. The runtime is included: no WSL, separate Python installation, compiler or source checkout is needed. Slay the Spire 1, ModTheSpire and BaseMod are required and are not redistributed in this package.

## Workshop

Subscribe to the [solver item](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843) plus ModTheSpire and BaseMod. Wait for Steam to finish downloading, choose Play with Mods, then enable BaseMod and STS1 Combat Solver in ModTheSpire. Communication and state export are integrated into one solver JAR. Remove the previous separate CommunicationMod.jar and SteamStateExport.jar copies supplied with older solver versions.

## Local package

Close the game. Copy STS1CombatSolver.jar and the whole `sts1-solver-runtime` directory into the game's `mods` directory. Keep the runtime beside `STS1CombatSolver.jar`. Enable BaseMod and STS1 Combat Solver. Update the entire package together while the game is closed; do not mix a newer JAR with older runtime files.

Your language preference persists. Logs, diagnostic exports and voluntary experience recordings live in `%LOCALAPPDATA%\STS1CombatSolver`, outside the installation directory. F6 exports diagnostics; Shift+F6 finalizes experience parts. Experience recording starts Off each launch. Nothing uploads automatically. Read PRIVACY.md before sharing files. Removing or unsubscribing from the mod leaves your local data intact; delete that folder yourself after exiting the game if you no longer need it.

If the backend is missing, verify Steam files or reinstall the complete package. If a fight fails, stop with F9 and export diagnostics with F6. Other characters and mods that change combat rules are not supported. The current scope is Ironclad and colorless cards; four-character coverage and RL are active development goals, with RL research and experiments underway.

---

适用于 Windows 10/11 x64。包内自带后台，无需 WSL、单独安装 Python、编译器或源码。需另行安装游戏本体、ModTheSpire 和 BaseMod，包内不重新分发这些组件。

## 工坊安装

订阅[求解器工坊条目](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)、ModTheSpire 和 BaseMod。等 Steam 下载完成后选择“使用模组启动”，在 ModTheSpire 中勾选 BaseMod 和 STS1 Combat Solver。通信桥和状态导出已合并到同一个求解器 JAR；升级时移除旧版求解器附带的独立 CommunicationMod.jar 和 SteamStateExport.jar。

## 本地包安装

先退出游戏，将 STS1CombatSolver.jar 和完整的 `sts1-solver-runtime` 目录放到游戏的 `mods` 目录中。运行时必须与 `STS1CombatSolver.jar` 相邻。勾选 BaseMod 和 STS1 Combat Solver。更新时退出游戏并替换整套文件，不要混用新版 JAR 和旧版后台。

语言设置会保留。日志、诊断包与自愿录制的经验写入 `%LOCALAPPDATA%\STS1CombatSolver`，不会被模组更新覆盖。F6 导出诊断；Shift+F6 封存经验分卷。经验每次启动默认关闭，不会自动上传。分享文件前阅读 PRIVACY.zh-CN.md。卸载或退订模组会保留本地数据；不再需要时，退出游戏后自行删除该目录。

若提示后台缺失，请验证 Steam 文件或重新安装完整包。战斗出错时按 F9 停止，再按 F6 导出诊断。当前只支持战士牌和无色牌，不支持其它职业或修改战斗规则的模组。四职业覆盖和 RL 是正在推进的目标，RL 研究与实验已经在进行。
