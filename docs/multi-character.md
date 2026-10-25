# 四职业战斗支持：范围与分工

Last confirmed: 2026-10-15，基于分支 `fix/search-timeout-handling`（6dc1e87）的静态阅读，未编译、未运行测试。
所有里程碑和排序都是提议（proposed），发布顺序仍是 [architecture.md](architecture.md) 里的待决事项。产品目标见 [product.md](product.md)。

本文回答三件事：把战斗求解从铁甲战士扩到静默猎手、故障机器人、观者，原生模拟器现在缺多少；模拟器之外哪些地方写死了铁甲战士；外部协作者怎样安全地认领一块。不含任何实现。

路径缩写：`ES/` = `candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source/`，`AG/` = `candidates/sts-ironclad-agent/`。

## 1. 模拟器盘点

### 1.1 总体

模拟器内核来自 sts_lightspeed。它的枚举和静态表已经覆盖四个职业，战斗逻辑几乎只实现了铁甲战士。

- 枚举与静态属性表覆盖全部 371 个 `CardId`：名称、Java 字符串 ID、颜色、稀有度、类型、基础伤害、虚无、固有、消耗、保留、目标、费用（`ES/include/constants/Cards.h`：enum 12，`cardStringIds` 422，`cardColors` 424，`cardBaseDamage` 433，`isCardEthereal` 466，`doesCardExhaust` 534，`doesCardSelfRetain` 633，`cardTargetsEnemy` 674，`getEnergyCost` 704）。表项存在，非铁甲战士的数值未逐张核对。
- 四职业的奖励牌池、起始卡组、起始遗物都已建好：`ES/include/constants/CardPools.h:72-74`（`RarityCardPool`，每职业 71 张，铁甲 72），`ES/src/game/GameContext.cpp:526-570`（`initPlayer`），`ES/include/constants/RelicPools.h:21`（起始遗物）与 27-60（四个职业的遗物池）。
- 牌的行为只写在三个 switch 里：`BattleContext::useAttackCard`（`ES/src/combat/BattleContext.cpp:972`）、`useSkillCard`（1226）、`usePowerCard`（1538）。新牌的落点就是这里加 `case`，再按需补 `Cards.h` 的属性表。

### 1.2 卡牌

当前数字以 `overlay/coverage_manifest.json` 为准（`python tools/coverage_manifest.py` 生成，CI 检查它是否过期）；下表是 2026-10-18 的快照，与清单一致。

按奖励牌池统计（脚本对 `RarityCardPool` 与三个 switch 的 `case` 取差集得到）。

| 职业 | 奖励牌池 | 已实现 | 缺失 | 起始牌 |
| --- | --- | --- | --- | --- |
| 铁甲战士 | 72 | 72 | 0 | 全部可用 |
| 静默猎手 | 71 | 2 | 69 | Survivor、Neutralize 缺 |
| 故障机器人 | 71 | 71 | 0 | 全部可用 |
| 观者 | 71 | 1 | 70 | Eruption、Vigilance 缺 |

- 静默猎手已有的两张是 Wraith Form（`BattleContext.cpp:1538` 段）和 Piercing Wail。后者不在基础 switch 里，是 `overlay/build_native.py:89` 在构建时注入的。观者已有的是 Vault。
- 四个职业的基础 Strike 和 Defend 都已实现。
- 衍生牌尚不可打出共 9 张：Shiv、Miracle、Smite、Safety、Insight、Beta、Omega、Expunger、Through Violence。Wish 的三个结果牌 Become Almighty、Fame and Fortune、Live Forever 也缺。
- 状态牌和诅咒牌（Burn、Decay、Doubt、Regret、Shame 在 `useNoTriggerCard`，`BattleContext.cpp:932`；Void 在 `ES/src/combat/CardManager.cpp:403`）与职业无关，已可用。

遇到未实现的牌时，模拟器在 `useAttackCard`、`useSkillCard`、`usePowerCard` 的 `default` 分支抛 `std::runtime_error("attempted to use unimplemented card: ...")`（`BattleContext.cpp:1216-1219`、`1529-1533`、`1630-1634`）。`sts_asserts` 在 `ES/include/sts_common.h:8` 无条件打开，所以这是常开的行为。异常经 pybind 变成 `RuntimeError`，在搜索中途冒出，后端把它当作一次求解错误上报。药水分支有同类的 `assert(false)`（`BattleContext.cpp:2447-2452`）。目前靠 Mod 和后端在战斗开始前按职业拒绝（见 2.1）来避免走到这里，模拟器自身没有"这张牌能不能算"的前置检查。

### 1.3 职业专属机制

| 机制 | 现状 | 位置 |
| --- | --- | --- |
| 中毒（静默） | 已有：敌人 `POISON` 状态、结算动作、Noxious Fumes、Envenom、Specimen 转移 | `ES/src/combat/Actions.cpp:944-961`，`Player.cpp:800`，`Monster.cpp:349`、`376` |
| 小刀 Shiv（静默） | 能生成（Infinite Blades、Ninja Scroll），Shiv 本身不可打出 | `Player.cpp:705`，`BattleContext.cpp:396`；`Cards.h` 有 `SHIV` |
| 弃牌钩子（静默） | 只有手动弃牌时 Tactician、Reflex 的触发和本回合弃牌计数 | `BattleContext.cpp:2861`，`Player.h:102` |
| 弃牌选择（静默） | 缺：`CHOOSE_DISCARD_CARDS` 在枚举里但无处理；Tools of the Trade 的弃牌被注释掉 | `ES/include/combat/InputState.h:21`，`Player.cpp:804-806` |
| Accuracy | 状态在枚举里，注释 `todo implement` | `PlayerStatusEffects.h:58` |
| 宝珠（机器人） | 已有：`OrbState` 数组（类型、黑暗累计）、channel（满栏先激发最旧）、栏位增减、四种宝珠的被动与激发、集中可正可负 | `Player.h`、`Player.cpp` |
| 宝珠相关动作 | `ChannelOrb`、`EvokeOrbAction`、`IncreaseOrbSlots`、`EssenceOfDarkness`、`TriggerStartOfTurnOrbsAction`、`TriggerEndOfTurnOrbsAction`、`ImpulseAction`、`DarkImpulseAction` 均已实现 | `Actions.cpp` |
| 回合开始宝珠、Loop | 已接入：等离子在回合开始给能量；Loop 每层触发最旧珠的完整被动 | `BattleContext.cpp`、`Player.cpp` |
| 集中 Focus | 已有字段和状态，可加减 | `Player.h:59`、`217`、`353` |
| 姿态（观者） | `stance` 字段和 `changeStance<S>()` 只赋值；`Actions::ChangeStance` 返回空 `Action`，所有经动作切换姿态的路径都是空操作 | `Player.h:49`、`521-523`，`Actions.cpp:134-136` |
| 姿态的伤害效果 | 已有：怒（Wrath）出伤乘 2、受伤乘 2，神（Divinity）出伤乘 3 且回合开始退出 | `BattleContext.cpp:2784-2790`、`2179-2182`，`Monster.cpp:636` |
| 姿态的其余效果 | 缺：平静（Calm）退出给能量、回合末姿态钩子、Mental Fortress、Rushdown、Nirvana 等 | `BattleContext.cpp:2121`（`todo`） |
| 真言 Mantra | 能累加，未到 10 转神；Devotion 同 | `Player.cpp:602-603`、`792`，`BattleContext.cpp:283` |
| 预见 Scry | `InputState::SCRY` 在枚举里，没有任何实现；Foresight、Melange 都是注释 | `InputState.h:23`，`Player.cpp:690-694`，`BattleContext.cpp:2874-2876` |
| 保留 | 已有：自带保留、单次保留位、回合末保留流程 | `Cards.h:633`，`BattleContext.cpp:2489-2515`；Establishment 缺（`Player.cpp:478-479`） |
| 姿态药水 | `CHOOSE_STANCE_ACTION` 状态无处理，搜索也不枚举它 | `BattleContext.cpp:2430-2431` |

缺少状态定义的 power 至少有 9 个：Storm、Heatsinks、Self Repair、Machine Learning、Mental Fortress、Rushdown、Study、Nirvana、Nightmare。注意容量：玩家状态位掩码是 64+32=96 位（`Player.h:63-64`、`282-290`），当前枚举用了 87 个（`PlayerStatusEffects.h`），第一批新 power 就会越界，需要先扩容。`PlayerStatusAmounts` 本身有 256 个槽，不是瓶颈。

搜索层只枚举 `PLAYER_NORMAL` 和 `CARD_SELECT` 两种输入状态，其余走 "invalid input state"（`ES/src/sim/search/BattleScumSearcher2.cpp:384-399`）。新增的姿态选择、预见、弃牌选择都要加进枚举和 `Action.cpp`。已有的 `CardSelectTask`（`ES/include/combat/CardSelectInfo.h:14-35`）里有 Hologram、Seek、Recycle、Meditate、Setup、Secret Technique 等名字，但对应牌没有实现，选择处理函数只有部分存在（`BattleContext.cpp:2985-3190`）。

### 1.4 遗物

遗物池按职业区分已经正确（`RelicPools.h`）：通用池每职业相同，职业专属遗物各 9 到 11 个。

| 职业 | 专属遗物（含起始） | 已有钩子 | 无钩子或依赖空实现 |
| --- | --- | --- | --- |
| 静默猎手 | 11 | Ring of the Snake、Ring of the Serpent、Ninja Scroll、Twisted Funnel、Wrist Blade、Paper Krane、The Specimen | Snecko Skull、Tingsha、Tough Bandages、Hovering Kite |
| 故障机器人 | 9 | Cracked Core、Frozen Core、Data Disk、Runic Capacitor、Symbiotic Virus、Nuclear Battery、Inserter、Gold-Plated Cables、Emotion Chip | 无 |
| 观者 | 9 | Pure Water、Holy Water（依赖不可打出的 Miracle）、Damaru、Teardrop Locket（依赖空的姿态切换）、Cloak Clasp、Duality | Golden Eye、Violet Lotus 无钩子；Melange 的预见为 `todo` |

钩子位置：`BattleContext.cpp:81-83`、`116-132`、`190-193`、`274-367`、`392-454`、`1881`、`2053-2060`、`2759`，`Monster.cpp:349`、`621`，`Player.cpp:369`、`601-634`。

### 1.5 药水

职业药水大多有分支：Focus Potion（`BattleContext.cpp:2367`）、Bottled Miracle（2291）、Stance Potion、Ambrosia、Essence of Darkness、Liquid Memories 等。其中 Stance Potion 和 Ambrosia 仍依赖观者姿态机制；Essence of Darkness 已随 M3 宝珠系统实现。

### 1.6 规模判断

三个职业的缺牌数相当（69、71、70），差别在共享机制的欠债：

- 静默猎手：几乎不需要新子系统。中毒已有，缺 Shiv 打出、弃牌选择状态和 Accuracy。
- 观者：需要姿态状态机（补齐 `ChangeStance`、平静退出、真言转神）、预见（新输入状态加搜索枚举）、Wish 和 Alpha 链，约是中等规模。
- 故障机器人：宝珠系统（数组、channel 与 evoke、被动、集中、回合触发、闪电随机目标与 Java 的随机数消耗次序一致）、全部机器人牌与职业遗物已在 M3 落地，剩余的是真值校准与职业开关。

## 2. 模拟器之外的铁甲战士假设

### 2.1 开关：三处必须同步

- `overlay/backend.py:36` 的 `SUPPORTED_CLASSES = {'IRONCLAD': '战士'}`，`view_from_state`（80-90）据此拒绝其他职业。
- `overlay/src/sts1solver/SolverMod.java:197-203` 的 `SUPPORTED`，以及 `supported()` 的调用点：287、503、560、650、1078、1109-1111（面板提示"战斗引擎目前支持战士；预测仍可用"）、1268、1384。
- 局外自动：`OutsidePacket.java:62-63`（`build` 抛 "Student only supports Ironclad"）与 139（`plannedRoute` 对非铁甲返回空路线）。

三处互不引用，只靠注释"keep in sync"。提议让一个测试同时断言三处一致。

### 2.2 状态捕获与导入

- 导入固定为铁甲：`ES/bindings/slaythespire.cpp:228` 写死 `bc.player.cc = CharacterClass::IRONCLAD`（这会影响发现、随机牌、随机药水用的职业，见 `Actions.cpp:563`、`580`、`588`，`BattleContext.cpp:163`、`2333`、`2898`）。
- 快照不含宝珠、姿态、保留位：`AG/steam/steam_mcts.py:544-631`（`build_snapshot`）只带血量、格挡、能量、powers、四堆牌、怪物、遗物、药水、随机数。`slaythespire.cpp:61-65`（`snapshotCard`）和 228-380 的玩家导入也没有这些字段。
- 游戏侧导出：CommunicationMod 已导出宝珠（`references/CommunicationMod/src/main/java/communicationmod/GameStateConverter.java:735-739`，字段 id、名称、evoke、被动），但不导出姿态，也不导出牌的保留位。`AG/steam/state_export_mod/src/steamstateexport/CombatStatePatch.java:57-97` 补充的是随机数、遗物状态、怪物内部字段，没有这些。`overlay/src/sts1solver/CardExportPatch.java:13-23` 给牌补了费用、伤害、格挡、`misc`，没有保留。
- 卡牌别名：`steam_mcts.py:18` 的 `CARD_ALIASES` 与 `Distill2.java:89` 只列了 `Strike_R`、`Defend_R`、`Ghostly`。`Strike_G/B/P`、`Defend_G/B/P` 以及 Java ID 与枚举名不一致的牌（如 Underhanded Strike、Gash、Steam、Venomology、Wireheading）需要补，`overlay/test/original-game-ids.json` 只收了 131 个铁甲和无色牌 ID。
- 牌池导出本身与职业无关：`overlay/src/sts1solver/RecoveryExportPatch.java:41-49` 读取实时牌池，`overlay/backend.py:173-174` 和 `overlay/native.cpp:188-196` 以此覆盖发现和随机牌（`overlay/build_native.py:54-62`）。这一路径换职业后不用改，前提是所有牌 ID 都能映射。
- 步骤核对只比较血量、格挡、能量、牌堆、怪物、药水、随机数：`steam_mcts.py:676-692`（`assert_snapshot_matches`）与 `AG/sim_patch/parity/adapter.py:128-146`（`simulator_state`）。宝珠、姿态、集中、真言的偏差不会被发现，所以这些字段进入模拟器的同时必须进入核对。
- Python 绑定没有暴露宝珠和姿态的读取接口（`slaythespire.cpp` 中无相关 def）。

### 2.3 搜索评估与启发式

- 终局评估以血量为主，不依赖职业（`ES/src/sim/search/BattleScumSearcher2.cpp:625-642`、`686-696`），可复用。未结束的节点只看怪物血量比例，所以姿态、宝珠、Focus 等铺垫价值不会被计入，影响搜索质量，不影响正确性。
- 出牌顺序先验按牌写死，覆盖铁甲牌和少数通用牌（`ES/src/sim/search/ExpertKnowledge.cpp:9` 起，被 `BattleScumSearcher2.cpp:338` 使用）。新职业的牌默认没有先验，可以先留空，之后按需要补。
- `overlay/native.cpp:71-76` 的"战斗价值折算"常数（能量、Pen Nib 等）是手设值。
- 战后恢复：`ES/include/combat/VictoryHp.h:28-44` 只含 Burning Blood、Black Blood、Face of Cleric 等，对其他职业不缺项；`overlay/recovery.py` 与职业无关。
- 起始遗物触发和起始卡组是 `GameContext` 层的东西，产品只在战斗中使用模拟器，局外选择来自实时游戏，所以这一层不必为产品完工。

### 2.4 局外规则、路线与蒸馏模型

- 路线规划 `RoutePlanner.java` 不读职业，只是被 `OutsidePacket.java:139` 挡住。
- 局外网络 Distill2 的特征词表覆盖模拟器全部 371 张牌（`overlay/resources/sts1solver/distill2-schema.json`），但权重来自铁甲数据（README 说明为早期训练结果）。换职业没有现成模型。`Distill2.java:104-124` 对未知 ID 直接抛错。
- 写死铁甲内容的规则：删牌和变牌只允许诅咒、Strike、Defend、Bash（`OutsidePacket.java:471-472`、`856-857`，`EventRules.java:55-56`）；遗物评分是"铁甲自动驾驶的草稿阈值"（`RelicScore.java:10`、`37-41`、`71-88`）；规则校对单整体面向铁甲（`docs/outside-rules.md:1`）。
- 提议：局外自动驾驶保持铁甲专属，不与战斗支持绑定。这与 architecture.md 的路线第 2 项一致，把"战斗支持"和"自动驾驶支持"分成两个独立的发布声明。

### 2.5 包装与文案

- 构建和打包只引用 `candidates/sts-ironclad-agent` 路径，是目录名，不涉及职业逻辑（`tools/build_solver_runtime.py:13`、`package_*_runtime.py`）。
- `tools/check_solver_runtime.py:21` 用 `CharacterClass.IRONCLAD` 做冒烟检查。
- 面向用户的文案写着铁甲：`WORKSHOP_DESCRIPTION.md:1-3`、`README.md` 的"What it does"和"Full autopilot"、`overlay/resources/localization/*.tsv` 的"战士"。README 的支持范围表述只在某职业发布时才改。

## 3. 里程碑顺序（proposed）

原则：每一步合并后产品照常可用，没有完成的职业继续报告"战斗引擎目前支持…"，不改变铁甲行为。

**M0 打地基。** 不改变任何用户行为。职业开关一致性检查、覆盖清单与求解前拒绝、按职业分发、状态位扩容、快照可选字段与导入器读职业、校准运行器骨架已在 PR #8 完成；第一批四职业校准用例和无头引擎参考适配器已发布（见 [真值校准与夹具](truth-calibration.md)）；ID 映射表仍未做。覆盖清单的求解前拒绝依赖 Java ID 与引擎名一致，映射表补齐前，ID 不一致的缺失牌不会被拒绝。
- 把"支持的职业"集中成一份清单，加测试断言 2.1 的三处一致。
- 加覆盖清单工具：从 `RarityCardPool`、三个 switch、遗物钩子生成"已实现和未实现"表，并加一个检查：声明支持的职业必须没有未实现的奖励牌、起始牌、衍生牌、专属遗物。
- 把"未实现的牌"从常开的运行时异常，改为后端在求解前的明确拒绝（报告限制而不是求解错误）。
- 补卡牌和遗物 ID 的映射表与测试（`Strike_G/B/P`、`Defend_G/B/P` 及 Java 与枚举名不一致的牌）。
- 快照格式预留可选字段 orbs、stance、retain，生产端和消费端同版本改动（architecture.md 的兼容性要求）。
- 扩容玩家状态位掩码，给"状态数不得超过容量"加编译期断言（`Player.h:279` 有被注释掉的 `static_assert`）。
- 把导入处的 `cc = IRONCLAD` 改为读快照里的职业。

**M1 静默猎手（proposed，建议先做）。** 理由：缺牌数与另两个职业相当，但共享机制欠债最小。中毒、Shiv 生成、手动弃牌钩子、Ring of the Snake 等已有，不需要新子系统，改动集中在牌的 switch 和一个弃牌选择状态。弃牌选择、Shiv、出牌后钩子也是后两个职业会复用的基础。
- M1a：起始牌（Survivor、Neutralize）、Shiv、不引入新机制的牌。
- M1b：中毒和 Shiv 系的牌，Accuracy、Envenom、Catalyst 等。
- M1c：弃牌选择（含搜索枚举）与依赖它的牌，Tools of the Trade、Nightmare 等。
- M1d：专属遗物（Snecko Skull、Tingsha、Tough Bandages、Hovering Kite）和药水。
- M1e：验收集通过后，开静默猎手的支持开关，同步 README 和文案。

**M2 观者（proposed）。** 姿态状态机（ChangeStance、平静退出、真言转神）、预见、Miracle 与 Smite 等衍生牌、Wish、Alpha 链、Establishment、Rushdown 和 Mental Fortress。把 stance 加入导出、快照和核对。
**M3 故障机器人（proposed）。** 宝珠子系统、Focus 联动、全部机器人牌与遗物。把宝珠加入导出（CommunicationMod 已有）、快照和核对。闪电随机目标的随机数流需要和 Java 逐步对齐。

M2 与 M3 的先后取决于协作者兴趣；若只能选一个，观者规模小于机器人。每个里程碑的职业开关，都只在该职业的验收集通过后才打开。

## 4. 验证方式

现有检查（不需要游戏的部分在 CI 里跑，见 `.github/workflows/native-runtime.yml`，对 `candidates/**`、`overlay/**`、`tools/**` 的 PR 触发，三个操作系统、Python 3.11 和 3.14）：

- `tools/build_solver_runtime.py`：构建原生引擎，并在第 29 行运行 CTest。现有 CTest 目标见 `AG/combat_engine/CMakeLists.txt:55-68`：`thief_value`、`victory_hp`、`pen_nib`、`chaos_double_tap`。它们是"构造 `GameContext`、初始化一场战斗、断言结果"的独立程序（见 `AG/combat_engine/tests/pen_nib_test.cpp`），新职业的单牌或单机制单元测试按这个模板加。
- `tools/check_headbutt.py`、`check_armaments_identity.py`、`check_corruption_snecko.py`：用记录下来的原版游戏前后状态回放（夹具在 `overlay/test/*.json`）。这是"原版结果作为真值"的现成模式，新职业的牌按同样方式加夹具。
- `AG/sim_patch/alignment/tests/compare_cards.py`、`compare_powers.py`：用导入的原版夹具比较单个动作的结果，需要 `ALIGNMENT_BUILD` 与 `STS_AGENT_ROOT` 环境变量。
- `tools/check_backend_launcher.py`、`check_backend_protocol.py`、`check_solver_runtime.py`：后端启动、拒绝不支持状态、取消、引擎身份。开关变化时 `check_backend_protocol.py` 需要加职业用例。
- Java 侧检查（`overlay/test/*Check.java`，由 `overlay/build.py:76-100` 在本地构建时运行，需要 JDK 和游戏 JAR）：`OutsidePacketCheck` 覆盖局外门禁，改动 2.1 时要跟着改。
- 双 Mod 构建（`docs/mod-pair-ci.md`、`mod-pair.yml`）是自托管机器上的手动流程，不接受外部 PR。

按里程碑：

| 里程碑 | 检查 |
| --- | --- |
| M0 | 覆盖清单检查；一致性测试；ID 映射测试；铁甲的全部现有检查不变；`check_backend_protocol.py` 增加"其他职业被拒绝"用例 |
| M1、M2、M3 的每一小步 | 每张或每组牌一个 CTest 程序；至少一组原版夹具（见下）；该职业的 ID 映射；快照往返测试（导入后核对通过）；覆盖清单检查通过 |
| 打开某职业的开关 | 该职业的验收集（战斗开始的原版状态，加期望的原版结果）全部通过；人工在游戏里跑一遍（动画和菜单时序，沿用 architecture.md 路线第 1 项的要求） |

### 与 Java 真值的差分

原版 Java 真值引擎是私有的，不在本仓库里，也不提供给协作者。协作者用自己的游戏搭真值，先用维护者公开的校准用例校准，再用它生成原生夹具；格式、适配器协议和流程见 [真值校准与夹具](truth-calibration.md)。校准观测已包含宝珠和姿态；Mod 的快照导出还没有（见 2.2）。

只读检查结果（未运行）：私有真值引擎的命令行支持选择职业。`HeadlessCli.java:124-127` 把 `new <seed> <CLASS> <ascension>` 里的职业名解析为 `AbstractPlayer.PlayerClass`（SILENT 映射为 THE_SILENT），`GameKernel.java:127` 的 `newRun` 接受任意职业，`unlockAll` 的说明覆盖四个职业。这只证明能开局，三个职业的战斗是否与原版一致没有验证，也没有复制其中任何源码。

真值还没校准通过时，协作者仍可以做：按游戏规则写 CTest 单元测试，核对牌面数值和描述，以及检查覆盖清单。但这些不能代替差分，不应作为打开开关的依据。

## 5. 协作方式

**分支与 PR。**
- 代码和 CI 要求见 [CONTRIBUTING](../CONTRIBUTING.md)。PR 目标分支为 `main`。
- 协作者 fork，从确认的基线开一个按职业和里程碑命名的分支，一个 PR 对应一个小步（如"静默猎手 M1a"），附上覆盖清单的变化和新增的检查。
- 不要用 `pull_request_target`，`docs/mod-pair-ci.md` 已说明原因。

**所有权划分。** 冲突集中在几处共享文件，建议按下表分工，共享文件的改动尽量小，并由 M0 先落地结构。

| 区域 | 说明 | 建议 |
| --- | --- | --- |
| `Cards.h` 的属性表 | 每个数组是单行长表，按枚举顺序，改动极易冲突 | 只改自己职业的牌的数值，不重排枚举；考虑 M0 先把表拆成按职业的文件或每牌一行 |
| 三个 `use*Card` switch | 同一函数里多人加 `case` | M0 把每个职业的 `case` 拆到各自的函数或文件，如 `useSilentCard`，再由主 switch 分发 |
| `PlayerStatusEffects.h` 的状态枚举与位掩码 | 追加会改变数值，且有 96 位上限 | 由维护者或 M0 统一追加，协作者不自行插入 |
| `Player.cpp` 的回合钩子 | 多个职业改同一个 switch | 每次小改动前先同步基线 |
| 搜索枚举 `BattleScumSearcher2.cpp`、`Action.cpp` | 新输入状态（弃牌选择、预见、姿态选择） | 状态由先到的职业添加，后来者复用 |
| `backend.py`、`SolverMod.java`、`OutsidePacket.java` 的开关 | 三处同步 | 只在打开开关的 PR 里改，由维护者合并 |

一人一职业最稳妥，静默猎手先行；弃牌选择、Shiv 这类跨职业复用的基础件，放在首个职业的 PR 里，其他职业等它合并后再用。

**协作者需要在本地准备。**
- 自己的《杀戮尖塔》正版副本（仓库不含游戏 JAR、字体、资源或 Java 源码，见 `THIRD_PARTY_NOTICES.md`）。
- ModTheSpire 和 BaseMod（README 要求另行安装）。
- JDK 9+、Python 3.11+、CMake 3.19+、C++17 编译器、`pybind11==3.1.0`（见 README 的原生源码构建）。
- 原生部分（CTest、覆盖清单、ID 映射、快照往返）不需要游戏；Java 检查和实机验证需要游戏、MTS 和 BaseMod。
- 若要生成夹具，还需要一个通过校准的真值引擎（见 [真值校准与夹具](truth-calibration.md)），没有的话只能做到单元测试这一层。

## 6. 未确定项

- 各职业缺牌数是按"三个 switch 的 `case` 与牌池的差集"得到的静态结果，没有编译或运行验证；非铁甲牌的 `Cards.h` 数值未核对。
- Java 卡牌 ID 与枚举名的不一致清单需要对照实际游戏导出确认，本文只给出已知的几例。
- 搜索对新职业的预算和启发式是否够用，需要有可玩的实现后才能评估。
- 验收集的组成与发布顺序，需要维护者决定（见 [产品 spec](product.md) 的待决问题）。
