# 双 mod 构建 CI

`tools/build_mod_pair.py` 构建当前求解器和指定本地 State Notes，运行各自检查及头槌、武装身份映射、日志回归，核对 JAR 内容与最终 ZIP 字节一致性。不会启动游戏、覆盖安装或发布工坊。联合原版游戏实测由玩家执行，manifest 明确保持 `pending_manual`。

本机入口（PowerShell，UTF-8）：

```powershell
$env:PYTHONUTF8='1'; $env:PYTHONIOENCODING='utf-8'
.\.venv\Scripts\python.exe tools/build_mod_pair.py --game 'D:\SteamLibrary\steamapps\common\SlayTheSpire' --state-mod 'E:\Projects\Opensource\塔1求解器\sts1-combat-pilot\state-mod' --output 'overlay/build/mod-pair-test-20261009'
```

输出目录必须是新目录，防止失败后把旧包误认为本次成功。需要原版游戏、Workshop MTS/BaseMod、JDK 9+、PowerShell 与已构建的求解器 Python/native 环境；增加 `--rebuild-runtime` 可重编原生后端，需要 pybind11、CMake 和 Windows C++ 编译工具。

工作流 `.github/workflows/mod-pair.yml` 仅手动触发主分支，使用 Windows x64 自托管 runner，额外标签 `sts1-mod-build`。既有公开多平台 CI 保持不变。两个源码不在同一公开仓库：State Notes 目前从 runner 本地检出构建，不自动访问 NAS、不自动切换其分支；manifest 记录两边 commit 与 dirty 状态。触发前更新 State Notes 到要测试的版本。

启用前，在专用测试 runner 的服务环境设置 `STS1_GAME_DIR`（游戏目录）和 `STS1_STATE_MOD_DIR`（State Notes 模块目录），重启 runner 使环境生效。需要机器的图形环境时另跑游戏实测，本工作流不启动 GUI。工作流已随 v0.4.3 推送到主分支，专用 runner 尚未注册，因此双 mod 工作流尚无云端运行记录；公开多平台 native CI 已运行通过。

该 runner 只用于可信构建，不运行公开 PR、fork 或自由输入的分支。本工作流不支持 PR 触发；不要改成 `pull_request_target` 后检出贡献者代码。若需要开放 PR 联合验证，改用隔离的私有测试调度环境。

产物为本机 native 测试包：求解器引用 runner 检出的源码和 `.venv` 绝对路径，必须在同一机器并保留该目录。后续构建会更新同一路径的后端，旧测试包也会使用更新后的后端；复现实测问题时保留整份检出和环境。它不适合跨机器分发，也不是公共发布包。失败阶段保存日志；只有全部检查通过才产生 ZIP。GitHub artifact 保留 14 天，仅包含两个自有 JAR、manifest、说明及检查日志，不上传游戏 JAR、测试驱动或玩家存档。

手动双 mod 实测重点：先拿蛋再选牌、头槌/空鸟笼选择结算、事件与遗物取舍、第三幕最后蓝钥匙，以及全自动期间打开记录界面再返回。State Notes 的自动化标记接口尚未与求解器接通；目前记录来源可能为 `unknown`，不作为真人训练数据。通过构建不代表这些联合场景通过。
