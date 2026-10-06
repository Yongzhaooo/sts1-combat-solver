# 无 WSL 分发：实现与剩余验证

## 高 DPI 本机修正（2026-10-06）

先前改为无边框全屏后，实际游戏窗口变成 8640×4860，而物理显示器为 3840×2160：DPI 不感知的游戏 Java 被 Windows 225% 缩放再次放大。代码检查未发现求解器/内置桥修改显示模式或全局 Settings 缩放；这次问题由本机显示配置调整触发。

仅对游戏自带 `jre/bin/java.exe` 设置当前用户兼容性 `~ HIGHDPIAWARE`。原值不存在，恢复记录为 `overlay/runtime/install-backup/single-mod-20261006/dpi-before.json`。未改变 Windows 全局缩放。新隔离实例仍启用求解器，实测窗口 3840×2160、DPI 216；原来的用户游戏进程仍为 8640×4860、DPI 96，需要完整重启游戏/启动器生效。用户进程未被关闭。该设置是本机排障，不由公开 Mod 自动修改注册表。


## 单模组安装更新（2026-10-06）

公开构建现为单个 `STS1CombatSolver.jar`，版本 0.2.0，唯一模组 ID 为 `sts1solver`，只声明 BaseMod 依赖。CommunicationMod 和 SteamStateExport 的类与补丁内置；求解器显式初始化内置通信桥，不注册独立的通信设置页，也不执行旧 CommunicationMod 配置中的外部命令。第三方许可证同时保留在 JAR 与分发目录中。

`tools/check_single_mod.py` 验证唯一元数据、唯一初始化入口、桥接类与许可证。隔离游戏只加载 BaseMod、求解器和测试驱动，完成种子 123 的第一场战斗，HP 80、无求解错误，并通过正常退出。实际游戏已替换为单 JAR，旧两个桥接 JAR 移入 `overlay/runtime/install-backup/single-mod-20261006/`，同处保留旧求解器和显示配置。运行目录及其它模组未更改。62 个安装运行时/JAR 文件与清单一致。

显示配置由 3840×2160、独占全屏、240 帧上限改为原生 3840×2160、无边框全屏、160 帧上限、VSync 开启；显示器当时为 3840×2160@160Hz。需要用户在实际游戏中确认显示效果。当前运行包为 `public-release/single-mod-20261006/content`；下方先前多 JAR 的记载为历史。尚未上传 Steam。


2026-10-06。长期方向是尖塔 RL，当前卡牌支持范围为战士牌和无色牌；猎手、机器人、观者需要重做底层模拟器。近期分发目标是用户订阅一个求解器工坊条目，获得三个 Java 模块及完整原生后台。ModTheSpire 和 BaseMod 仍是基础前置。

## 已实现

- `BackendRuntime.java` 通过 ModTheSpire 中已加载 JAR 的实际位置定位相邻运行时，`-BundledWindows` 构建不写开发机源码路径，也不调用 WSL。
- 日志和经验写入 `%LOCALAPPDATA%\STS1CombatSolver`，不受工坊安装目录或更新覆盖影响。
- Windows MSVC 的 C++ 核心、三个 pybind 模块和求解器原生扩展已构建。修正了标准容器迭代器与裸指针混用、变长数组和 class/struct 前置声明不一致；GCC 专用的复制性能测量入口在 MSVC 下不注册。没有扩展职业支持。
- 随包提供官方 CPython 3.14.8 x64 嵌入包、应用本地 VC142 C++ 运行库、必要 Python 模块与许可证。用户不需要源码目录或自己的 Python 环境。
- 实际运行暴露出此前源码导出漏掉了 `compare_cards.py`、`compare_powers.py`；已补入源码和运行包允许清单。单纯通过模块导入不足以证明完整部署，必须执行真实请求。

## 证据与未完成项

Windows 三项核心 CTest 与 Advisor 导入通过；清空开发工具 PATH 后，随包解释器仍能加载全部原生模块。隔离游戏仅加载基础依赖和测试驱动，使用原生后台在正常速度下完成种子 123 的第一场双虱虫战斗，进入奖励界面，战后 80 HP。测试驱动跳过新手教学，不修改战斗规则。

冷启动已修复。根因为 Windows 工作进程继承了主进程读线程正在阻塞读取的标准输入管道，子解释器在进入 Python 代码前卡住；与 CPython [bpo-34780](https://bugs.python.org/issue34780) 描述一致。`start_worker` 仅在创建 Windows 子进程时把 Win32 标准输入句柄设为 NUL，随后恢复；父进程既有的 Python stdin 文件描述符不变。工作进程仍用 multiprocessing Pipe/Event，不添加第三方运行依赖。

`tools/check_windows_bundle.py` 对复制到含中文与空格路径的运行包、最小 PATH，连续三轮验证首次立即求解、输入线程阻塞时重载工作进程、取消搜索、状态偏离拒绝、F6 诊断协议和 EOF 正常退出。全部通过。测试中的偏离状态 traceback 为预期拒绝。最终 CPython 3.14 扩展构建、三项 CTest、原生模块导入及 Java 构建通过；发布运行时更新为官方 CPython 3.14.8，ZIP SHA-256 与发布页一致。JAR 以 `-B` 启动，避免在工坊目录写入 pycache。

相关本地证据：`overlay/build/windows-native-build-5.log`、`overlay/build/native-protocol.stdout.log`、`overlay/build/native-protocol.stderr.log`、`overlay/smoke-native/smoke-state.json`。这些都不进入公开源码或用户运行包。MSVC 仍报告底层位移宽度和未初始化 mask 警告；本次构建通过不能排除对应规则风险。

2026-10-06 18:20（Europe/Berlin）：按用户要求安装至真实游戏 `D:\SteamLibrary\steamapps\common\SlayTheSpire\mods`。安装包为 `public-release/workshop-local-20261006/content`，75 个清单文件中 64 个运行时/JAR 文件逐项验证；实际安装的 Python 3.14.8 和 Advisor 导入通过。旧三个 JAR 与许可证备份在 `overlay/runtime/install-backup/windows-native-20261006-182020/`。其它模组与存档未改；仅关闭隔离测试实例。

本轮新的隔离游戏位于 `overlay/build/工坊安装 smoke/`，仅基础依赖加测试驱动，正常速度完成第一场战斗，HP 80、无求解错误。用户现在自行测试与其它模组的兼容性。跨机器、真实工坊订阅下载、升级和游戏崩溃后的恢复仍待验收；当前没有上传 Steam 或创建公开仓库。上传器已生成 `public-release/workshop-20261006/` 空工作区，尚未装填，也不代表已发布。

## 可复现构建

使用独立、全新的源码导出目录构建 Windows，避免与 WSL 的 CMake 缓存和原生模块混用。开发机需要 Windows x64 CPython 3.14、Visual Studio C++ Build Tools、CMake 及 pybind11；这些是构建要求，不是玩家端要求。

```powershell
$env:PYTHONUTF8='1'
$env:PYTHONIOENCODING='utf-8'
python -m pip install pybind11==3.1.0 cmake==3.31.10
python tools/build_solver_runtime.py
pwsh -NoProfile -File overlay/build.ps1 -BundledWindows -Game 'D:\SteamLibrary\steamapps\common\SlayTheSpire'
```

然后运行 `tools/package_windows_runtime.py --help`，传入该构建目录、官方嵌入包、VC142 redistributable 目录、pybind11 许可证与新的输出目录。打包器使用允许清单并校验 Python ZIP 和原生模块身份。实际采用的 Python 嵌入包 SHA-256：`a93abe456ab01bd96d7a085b3cdb6566b3063f4241360d114142fbdb07f0a310`。

方案依据：[CPython 嵌入分发说明](https://docs.python.org/3.14/using/windows.html#the-embeddable-package)。[ModTheSpire Loader](https://github.com/kiooeht/ModTheSpire/blob/master/src/main/java/com/evacipated/cardcrawl/modthespire/Loader.java)会扫描同一工坊目录内的多个 JAR，因此目前无需为一个工坊条目强行合并三个模块。

运行时版本依据：[Python 3.14.8 官方发布页](https://www.python.org/downloads/release/python-3148/)。
