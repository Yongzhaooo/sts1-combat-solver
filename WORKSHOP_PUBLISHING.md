# 尖塔 1 创意工坊发布记录

2026-10-20：发布 `v0.8.0-beta.3` 到 GitHub 和工坊条目 `3814773843`。PR #23 合并，发布提交 `4efd4e1b81e451d6b1b17a6ce17621294f2af4da`，tag `v0.8.0-beta.3`，Release https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.8.0-beta.3 。CI `39395851928` 全部通过（Mac 运行时取自该次运行：arm64 `954da4e4…`, x86_64 `0c745e87…`）；Windows 运行时本机构建，JAR `runtime=bundled`；`bundle-manifest.json` 83 个文件，`check_windows_bundle.py` 三轮通过。资产 `STS1CombatSolver-windows-v0.8.0-beta.3.zip`（sha256 `cf8bd2eb3ce1cf07cd1d01937dcc916fa2a5ea00a2d32ab87c704906352f5bb2`）, `STS1CombatSolver-macos-v0.8.0-beta.3.zip`（sha256 `fe6496f37eb0c9c8947097aa8dcba595950949474ab0763ce054adc5f5789465`），下载回读一致。工坊上传带 `-Dfile.encoding=UTF-8` 成功，`GetPublishedFileDetails` 回读标题、说明与配置一致，公开。由 sts1-mod-release 流程生成。

2026-10-18：发布 `v0.8.0-beta.2` 到 GitHub 和工坊条目 `3814773843`。PR #21 合并，发布提交 `7b3d3b5fea81e16e56a57a9e1ecf29784165b1b3`，tag `v0.8.0-beta.2`，Release https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.8.0-beta.2 。CI `39137575133` 全部通过（Mac 运行时取自该次运行：arm64 `c8c78546…`, x86_64 `81b48a6c…`）；Windows 运行时本机构建，JAR `runtime=bundled`；`bundle-manifest.json` 83 个文件，`check_windows_bundle.py` 三轮通过。资产 `STS1CombatSolver-windows-v0.8.0-beta.2.zip`（sha256 `861188f9cfa26e8623c74dc4ef7eb6ff44b4819af3560d76244a00310aa6f1bc`）, `STS1CombatSolver-macos-v0.8.0-beta.2.zip`（sha256 `7c58f26167774ac2650c7721f012f2b9ec20e16f2e213a2608a1a06b5d14eb07`），下载回读一致。工坊上传带 `-Dfile.encoding=UTF-8` 成功，`GetPublishedFileDetails` 回读标题、说明与配置一致，公开。由 sts1-mod-release 流程生成。

2026-10-17：按用户授权发布 `v0.7.0` 到 GitHub 和原工坊条目 `3814773843`。PR #17（分支 `release/v0.7.0`，含 `fix/neow-glowing-eye`）合并，发布提交 `cdea67ef07b047f4f1bcc0877a7e9a5172951531`，tag `v0.7.0`，Release 为 https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.7.0 。内容：小循环求解器 v1.7（`overlay/loop_beam.h`）；狂宴模式（战斗中获得的生命上限按两倍计价）；Mods → Config 面板（Boss 快速收尾、狂宴模式、测试模式、路线标注）；后台取消竞态修复；涅奥随机结果只在辉眼开启时显示；鸣谢七月流火的测试。验收：562 个真实第三、四幕 Boss 局面同 seed 配对旧版，死亡 55→46（救回 9、新增 0），非心脏胜局血量 +2.57（family 自助法 95% 区间 +1.82～+3.48），技术失败 0，总搜索时长 1.66 倍（超过事先写定的 1.5 倍门槛，用户确认接受）。检查：PR CI `38940830129` 八项全部通过；本机 CPython 3.14.8 加 VS 2019 Build Tools 构建 Windows 运行时，5 项 CTest 与 Python 检查通过；JAR 用 `build.py --bundled`（`runtime=bundled`），`overlay/build.py` 全部 Java 检查通过；嵌入式 Python 重新从 python.org 下载，哈希 `a93abe45…` 与打包脚本一致。Mac 运行时 ZIP 取自同一 CI（arm64 `fb38b8d1…`、x86_64 `30e82893…`，PR 头与合并提交树相同），`bundle-manifest.json` 77 个文件，`check_windows_bundle.py` 三轮通过。资产 `STS1CombatSolver-windows-macos-v0.7.0.zip`（sha256 `42b9453361d90645f62d043e157e2f167cf969b26c964f01db67c7646a1901ec`）与 `SHA256SUMS.txt`，下载回读一致。工坊工作区 `build/release-0.7.0/workshop-upload`（内容与资产相同，删去默认 image.jpg 保留预览图），说明 5,985 字节；上传带 `-Dfile.encoding=UTF-8`，返回 `Successfully updated workshop item`，退出码 0，日志 `build/release-0.7.0/steam-upload.log`。`GetPublishedFileDetails` 显示标题与配置一致、公开、更新时间 2026-10-16 17:45:40 UTC，说明含 v0.7.0 与鸣谢。本机工坊目录的同步（第 4 步）待 Steam 客户端完成。新开关尚未经过长时间实机对局。

2026-10-16：按用户授权发布 `v0.6.2` 到 GitHub 和原工坊条目 `3814773843`。基线是 `main`，不是 `integrate/v0.6-main`（后者缺心灵冲击修复、四角色基础和校准，已发布的 v0.6.0 来自 `main`）；`main` 上已合并的 v0.6.1（SuperFastMode、自动执行修复、香炉规则）从未单独发布，一并带入。PR #13（分支 `release/v0.6.2-20261016`）合并，发布提交 `ba8ab4f2f724bdce85ac9d4f7f4e8491e9783ae2`，tag `v0.6.2`，Release 为 https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.6.2 。内容：act-start 选路与 Neow 位置编码（`93e4d01`、`da21cbf`）；研究线 W19b 局外规则修正（`916fcc0`、`5007f8c`、`714cd5f`，含 Boss 遗物跳过后循环开箱、蓝钥匙、战斗外药水、路线规则）；W23 事件预读白名单与辉眼默认关闭（`3476fb3`，只影响新安装或未保存过设置的玩家）；修复 `916fcc0` 带入的两条战斗外药水提示乱码。未纳入：`codex/e1-contract`（训练专用）和私有真值引擎的 W20。检查：`overlay/build.py --bundled` 全部 Java 检查（含 `PolicyBoundaryCheck`）通过；PR CI `38860655043` 与 `main` push CI `38861008019` 全部成功；本机用 CPython 3.14.7 加 VS 2019 Build Tools 重建 Windows 运行时，5 项 CTest 与 CI 同款 Python 检查通过；JAR 用 `build.py --bundled` 构建（`runtime=bundled`，不含 `python=`/`backend=`/`log=`）。Mac 运行时 ZIP 取自 `main` push CI `38861008019`（arm64 sha256 `40fc0d09…`、x86_64 `c72bf767…`），`bundle-manifest.json` 77 个文件，`source_commit` 为发布提交，`check_windows_bundle.py` 三轮通过。资产 `STS1CombatSolver-windows-macos-v0.6.2.zip`（sha256 `a30ab07486b88ce38ec9956f006f66ab5f4158da0ca2297f3d8a697847016ceb`）与 `SHA256SUMS.txt`，下载回读一致，内容与工坊上传目录相同。工坊第一次上传返回 `SteamResult=InvalidParam`：`config.json` 的 description 为 8170 字节，超过 Steam 的 8000 字节上限（字符数只有 5483，中文每字 3 字节），条目未改动。把 v0.6.1 段里香炉每幕计数的数字细节删去（中英文各一处，说明里只留“香炉按每幕规则卡计数”，细节仍在仓库的 `WORKSHOP_DESCRIPTION*.md`），description 降到 7782 字节后重传，带 `-Dfile.encoding=UTF-8`，返回 `Successfully updated workshop item`，退出码 0，日志 `build/release-0.6.2/steam-upload.log`（首次失败日志为 `steam-upload-attempt1-invalidparam.log`）。`GetPublishedFileDetails` 显示标题中文正常、公开、标签 `gameplay`、更新时间 2026-10-16 08:32:34、说明含 v0.6.2 和辉眼默认关闭的说明；上传 JAR sha256 `5a81f8680837832a9d8a727bb0feeeca4ebb5631057a49070741ce826e5b4305`。写本条时本机 `workshop/content/646570/3814773843/STS1CombatSolver.jar` 仍是 10/15 的 v0.6.0（sha256 `535fc204…`），Steam 客户端尚未同步，第 4 步未完成。规则修正已通过离线检查与 CI，尚未经过长时间实机对局；游戏内整局验收由用户执行。

2026-10-15：按用户授权发布 `v0.6.0` 到 GitHub 和原工坊条目 `3814773843`。发布提交 `c3781364a9b7f28b5a47696f4cc8a5cc70b79ac8`（PR #10），tag `v0.6.0`，Release 为 https://github.com/Yongzhaooo/sts1-combat-solver/releases/tag/v0.6.0 ，资产 `STS1CombatSolver-windows-macos-v0.6.0.zip`（sha256 `8f972ff231d7b136b73125a7f2b6bfc50105b48ef695388ee935eefc0ecae36f`）与 `SHA256SUMS.txt`，下载回读校验通过。Windows 运行时在本机用 CPython 3.14.7 加 VS 2019 Build Tools 从该提交的独立 worktree 构建，五项 CTest 与 CI 同款 Python 检查全部通过；JAR 用 `build.py --bundled` 构建（`runtime=bundled`，不含 `python=`/`backend=`/`log=`），经 `package_windows_runtime.py` 打包（嵌入 Python 3.14.8，哈希与允许值一致），两个 Mac 运行时 ZIP 取自同一提交的 CI `38741252434`（含 `check_macos_bundle.py`），并入后按 v0.4.3 的范围重算 `bundle-manifest.json`（77 个文件，记录 source_commit、ci_run、version）。`check_windows_bundle.py` 三轮通过。工坊上传工作区为 `build/release-0.6.0/workshop-upload`，删去默认 `image.jpg` 以保留现有预览图；标题、标签和可见性沿用条目现值，说明替换 v0.5.0-test 段为 v0.6.0。上传命令带 `-Dfile.encoding=UTF-8`，返回 `Successfully updated workshop item`，退出码 0，日志 `build/release-0.6.0/steam-upload.log`；`GetPublishedFileDetails` 显示标题中文正常、公开、更新时间 15:24、说明含 v0.6.0。上传后约两分钟，本机 `workshop/content/646570/3814773843/STS1CombatSolver.jar` 仍是 10/12 的 0.5-test 版，Steam 尚未同步，第 4 步未完成。规则修正已通过对照 Java 的离线重放与 CI，尚未经过长时间实机对局。

2026-10-12：`v0.5.0-test` 的 GitHub Release 和工坊条目 `3814773843` 都发布成了开发版 JAR。JAR 内 `solver.properties` 写的是本机 WSL 路径（`python=/mnt/e/.../.venv/bin/python`、`backend=`、`log=`），没有 `runtime=` 行，玩家启动后面板显示“后台求解器未响应”。本机工坊目录在 10/11 21:25 已同步到这个 JAR。原因是 `overlay/build.ps1` 未加 `-BundledWindows` 就构建，再把 JAR 手工放进 `build/release-0.5.0-test` 的旧包和 `workshop-upload/content`，跳过了 `tools/package_windows_runtime.py` 的 bundled 检查和 `tools/check_windows_bundle.py` 的清单校验。处理结果：GitHub 资产已用 `overlay/build.py --bundled-windows` 和打包脚本重建，三轮 bundle 检查通过，下载回读确认为 `runtime=windows-bundled`；`check_windows_bundle.py` 已改为拒绝开发版 JAR（提交 `d742783`）。同日经用户确认重新上传工坊：用 `build.py --bundled` 重建 JAR（`runtime=bundled`），换入 `workshop-upload/content`。原 `bundle-manifest.json` 是 10/10 生成的，手工替换过的 7 个文件哈希对不上，已按现有文件重算（范围不变，不含 Mac ZIP），三轮 bundle 检查通过。第一次上传没加 `-Dfile.encoding=UTF-8`，上传器按 GBK 读取 `config.json`，工坊标题和描述全部变成乱码；加上该参数重新上传后，Steam 接口和公开页面均显示正确中文，可见性为公开。上传器两次都返回 `Successfully updated workshop item`，退出码 0，日志在 `build/release-0.5.0-test/steam-upload.log`。遗留：两个 Mac 运行时 ZIP 仍是 10/09 构建，不含 0.5.0 的引擎修复，需由 CI 重建。

## 发布前必查的 JAR 模式

| 发布物 | 构建命令 | `solver.properties` 应为 |
| --- | --- | --- |
| 工坊（Windows＋Mac） | `overlay/build.py --bundled --game ...` | `runtime=bundled` |
| GitHub Windows ZIP | `overlay/build.py --bundled-windows --game ...` 后跑 `tools/package_windows_runtime.py` | `runtime=windows-bundled` |
| 本机开发 | `overlay/build.ps1` 不带参数 | 含 `python=`/`backend=`/`log=`，**不得发布** |

1. 不要把新 JAR 手工替换进旧发布目录或 `workshop-upload/content`。JAR 或后台变了就重新走打包脚本，再跑 `tools/check_windows_bundle.py`（路径过长会导致原生 DLL 加载失败，在短路径下运行）。
2. 上传前直接读 `workshop-upload/content/STS1CombatSolver.jar` 里的 `solver.properties`，必须是上表对应值，且不含 `python=`、`backend=`、`log=`。
3. 上传命令必须带 UTF-8：`jre\bin\java.exe -Dfile.encoding=UTF-8 -jar mod-uploader.jar upload -w <工作区>`。不带时中文 Windows 默认按 GBK 读取，标题和描述会变成乱码。上传后用 Steam 接口 `ISteamRemoteStorage/GetPublishedFileDetails` 或公开页面核对标题和可见性；更新刚提交的几十秒内，页面可能暂时显示“已被标记为隐藏”。
4. 上传后读本机 `steamapps/workshop/content/646570/3814773843/STS1CombatSolver.jar` 再核对一次，这是玩家实际拿到的文件。
5. `config.json` 的 description 按 UTF-8 字节计不得超过 8000（中文每字 3 字节），上传前用脚本断言；超出时上传器只返回 `SteamResult=InvalidParam`，不说明原因。

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
