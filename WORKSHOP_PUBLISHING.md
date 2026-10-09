# 尖塔 1 创意工坊发布记录

2026-10-10：按用户授权将 `v0.4.3` 更新到原条目 `3814773843`，State Notes 未发布。GitHub PR #4 已合并，发布提交 `d6476066b932fad056e99de8a2ee0e982966e2dc`，Release 为 https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.4.3 。CI `37997749960` 的六组平台/Python smoke 与两个 Mac 架构包全部通过；Windows 包另通过随包 Python、中文路径迁移、最小 PATH 与后端协议检查。上传工作区 `build/release-0.4.3/workshop-upload`，76 个文件及最终 ZIP 已校验，Mac Python 源码与提交 blob 一致，JAR 为 bundled 0.4.3，不含 State Notes。游戏内置上传器返回 `Successfully updated workshop item`，退出码 0；日志 `build/release-0.4.3/steam-upload.log`。公开页面抓取不可用，未独立核实页面缓存刷新；完整对局与双 mod UI 实测仍由用户执行。

2026-10-08：已按用户授权将 `v0.4.1` 更新到原条目 `3814773843`。上传工作区为 `build/release-0.4.1/workshop-upload`，来源提交为 `c297a68310dc003e330e4379652074498fd22030`，与成功的 CI 构建 `37835474461` 代码一致。上传前通过 76 个文件的 SHA-256 校验、JAR 版本和 bundled 配置校验，以及 Windows、macOS arm64/x86_64 运行时检查。游戏自带 `mod-uploader.jar` 返回 `Successfully updated workshop item`，退出码 0；公开页面抓取不可用，未独立核实页面缓存刷新。此次包括早期全自动爬塔、紧凑 UI 和腐化生成牌费用修复；Mac 全自动游戏内验收仍未完成。

2026-10-06：Windows x64 `v0.2.1` 已公开发布至 [Steam 创意工坊条目 3814773843](https://steamcommunity.com/sharedfiles/filedetails/?id=3814773843)，源码与 Windows ZIP 见 [Yongzhaooo 的 GitHub Release](https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.2.1)。上传配置位于 `public-release/potion-0.2.1-20261006/workshop-upload/config.json`，后续更新应复用条目 ID。公开页面已核对标题、用户指定封面和双语说明；Steam 订阅后的完整对局验收尚未完成。以下保留早期分发研究与发布步骤。

本次研究对象是 Slay the Spire 1，Steam App ID 为 `646570`。尖塔 2 的上传器和打包格式不适用。

## 已核实的上传入口

游戏安装目录包含 `mod-uploader.jar`。本机已通过以下上传入口创建工坊条目：

```powershell
java -jar mod-uploader.jar new -w sts1-combat-solver-workshop
java -jar mod-uploader.jar upload -w sts1-combat-solver-workshop
```

第一条只创建工作区。以生成的 README 和 config 为准填写标题、说明、预览图与可见性，并把发布文件放入生成的内容目录。第二条才会向 Steam 上传。可用的创建与上传参数应先分别通过 `new --help`、`upload --help` 核对。

游戏的上传器是发布入口，不需要为这个模组另外开发 Steamworks 上传服务。通用的可见性、条目更新与用户协议机制见 [Valve Workshop 实现文档](https://partner.steamgames.com/doc/features/workshop/implementation)。Steam 提示需要接受 Workshop 协议时，在账号内完成。上传流程的社区交叉参考是[尖塔 1 上传指南](https://steamcommunity.com/sharedfiles/filedetails/?id=1767940979)。

## 当前原生包

Windows 原生包已解决冷启动，随包包含 Python 3.14.8 与 C++ 扩展，不再依赖 WSL 或开发 checkout。安装与验证记录见 [WINDOWS_PORTABLE.md](WINDOWS_PORTABLE.md)。下节为此前开发版的分发缺口记录。

## 先前 WSL 开发包的分发缺口（历史）

当前模组通过 `wsl.exe` 调用项目里的 Python 虚拟环境。构建时写入 `solver.properties` 的 Python、后台和日志位置都绑定当前 checkout。工坊会将文件安装到订阅者自己的 workshop 目录，只有 Java JAR 不足以启动后台。

先发布 GitHub 的开发者源码包是可行路径。工坊正式版还需要完成以下部署工作：

1. 将后台发现改成相对发布目录或用户配置位置，日志写入用户可写目录。工坊更新不能覆盖用户配置、录音与诊断。
2. 明确第一版支持 Windows＋WSL，或另做原生 Windows 后台。前者需要清晰的首次安装指引；后者需要 Windows Python/C++ 扩展及加载方式，当前没有验证。Linux、macOS、Steam Deck 应分别构建和验收。
3. 固定 Python ABI、原生扩展和依赖版本。若打包 Python 运行时，保留它及所有第三方组件的分发许可。后台版本与 JAR 一起更新，避免旧模块被新导出格式加载。
4. 让 CommunicationMod 与 SteamStateExport 可安装且版本匹配。工坊页面列出 BaseMod 和相关依赖，说明仍需在 ModTheSpire 中勾选；不要把其它作者的 Workshop 项目直接重新上传成自己的内容。
5. 用另一份干净安装验证：订阅、初次启动、中文／英文、单步、药水路线、F9 停止、后台失败、退出游戏和工坊更新。没有开发 checkout 时也必须能工作。

本次源码导出不包含二进制，不代表这些部署工作已完成。

## 发布页面应说明的行为

建议名称：`STS1 Combat Solver — Ironclad`。首段介绍局部战斗搜索、药水路线和可选自动执行；写明只支持战士、有限预算、已知状态的模拟，以及玩家保留构筑和路线选择。

单独说明辉眼预测会读取种子／随机状态并揭示隐藏结果，自动模式可执行出牌和用药，F9 可停止。公开版 F6 导出错误报告，Shift+F6 收尾并打开自愿录制的经验包；默认不录音、不录制私人研究日志，也不自动上传。经验贡献与错误报告的用途、授权分别声明。地图、事件预测不应宣传成普通可见信息下的公平决策。

工坊页面已写入双语安装与限制说明，并链接公开 GitHub 仓库。[英文文案](WORKSHOP_DESCRIPTION.md)与[中文文案](WORKSHOP_DESCRIPTION.zh-CN.md)保留详细说明。

首次上传优先使用私有可见性，在 Steam 上核对实际内容和下载启动，再切换公开。生成的条目 ID 和上传配置保留在专用工作区；更新现有条目时复用该 ID，避免创建重复订阅项。

## GitHub 和工坊的分工

GitHub 发布源码、构建说明、许可证和问题追踪；C++ 模拟器保留独立来源和修复记录。工坊发布供玩家安装的模组与经验证的后台分发包。当前私有实验仓库保留原始实验、字幕、录音和诊断资料；公开仓库使用单独导出的源码树，不公开现有私有 Git 历史。
