# 参与开发 / Contributing

维护者主线是战士求解器和强化学习；静默猎手、故障机器人和观者的战斗求解器欢迎协作开发。联系邮箱：yongzhao.derek@gmail.com。

The maintainer focuses on the Ironclad solver and RL; Silent, Defect and Watcher combat solvers are open for collaboration. Contact: yongzhao.derek@gmail.com. The requirements below apply to every pull request.

## 先读

1. [四职业产品 spec](docs/product.md)：做成什么样，什么算"支持"。
2. [架构](docs/architecture.md)：目标结构和路线。
3. [四职业范围与分工](docs/multi-character.md)：模拟器现状、缺口位置、里程碑小步和分工。
4. [真值校准与夹具](docs/truth-calibration.md)：怎么校准自己的真值、怎么生成夹具。

动手前先在 issue 或邮件里说一下要认领哪个职业的哪一小步，避免两个人改同一处。

## 需要准备

- 原生部分（构建、CTest、夹具重放）：JDK 9+、Python 3.11+、CMake 3.19+、C++17 编译器、`pybind11==3.1.0`，步骤见 README 的原生源码构建。这部分不需要游戏。
- Java 侧检查和实机验证：你自己的《杀戮尖塔》正版、ModTheSpire、BaseMod。
- 生成夹具：你自己搭的、已经通过校准的真值引擎。

## 分支和 PR

- fork 本仓库，从集成分支 `main` 开分支，PR 也提到 `main`，分支名写职业和小步，例如 `silent/m1a-starter-cards`。
- 一个 PR 只做一个职业的一个小步（见范围文档第 3 节），不顺手重构共享代码，也不夹带无关修复。
- PR 说明写清：实现了哪些牌、遗物、能力；每项依据的 Java 类名和方法名（只写名字，不贴代码）；覆盖清单的变化；新增了哪些夹具和测试。

## 代码要求

1. **以原版 Java 为准。** 牌面描述、维基和你的印象都不算依据。拿不准的地方在 PR 里写出来，不要按猜测实现。
2. **仓库里不能有游戏代码。** 不贴反编译源码片段，不提交游戏 JAR、资源、字体、本地化文本。夹具只含状态数字和 ID。
3. **按职业放代码。** 新牌放进本职业的分发函数或文件；共享表只改自己职业的行，不重排枚举；新的玩家能力只追加在维护者预留的位置。
4. **没实现就保持未实现。** 不写近似实现，不让未完成的牌默认空操作。做不完的牌留在覆盖清单的"缺失"里，由求解前拒绝兜底。
5. **每个新实现都有覆盖。** 至少一个原生夹具覆盖它，覆盖清单里从缺失变为已实现；机制复杂的（宝珠、姿态、预见、弃牌选择）另加 CTest 单元测试，模板见 `candidates/sts-ironclad-agent/combat_engine/tests/pen_nib_test.cpp`。
6. **不改铁甲战士的行为。** 铁甲现有夹具和检查的结果不能变。确实发现铁甲侧的错误，单独开 PR，附 Java 依据。
7. **不放宽比较。** 不给严格比较器（`sim_patch/parity/core.py`）、随机数计数或终局校验加容差，不跳过字段。
8. **职业开关不动。** `backend.py`、`SolverMod`、`OutsidePacket` 里的支持开关只由维护者在该职业验收集通过后打开。
9. **源码规范。** 文本文件用 LF 行尾；改 `sim_patch/` 或 `overlay/build_native.py` 注入的内容时同步更新对应哈希；C++17，不引入新的第三方依赖；Python 优先用标准库。

## CI 要求

- PR 必须通过 `.github/workflows/native-runtime.yml` 的全部作业（三个操作系统、两个 Python 版本）。四职业 M0 之后，其中包括覆盖清单检查和全部原生夹具的重放。
- 不删除、不跳过已有检查。期望值只能来自已校准的真值，不能为了通过检查手改。
- 提交前在本地先跑：

  ```bash
  python tools/build_solver_runtime.py          # 构建原生引擎并运行 CTest
  python tools/check_backend_protocol.py        # 以及与你改动相关的其他 tools/check_*.py
  python tools/truth_calibration/run.py --adapter "<你的适配器>" fixtures/calibration   # M0 之后
  ```

- 合并前维护者会用私有真值重放你新增的夹具。结果不一致时，维护者贴出第一处差异的路径，双方一起定位；问题出在你的真值时，维护者会补一个校准用例。

## 许可

本仓库采用 MIT 许可（见 [LICENSE](LICENSE)）。你提交的代码和文档按同一许可发布。第三方组件的来源和许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，移动这些文件时保留原署名。
