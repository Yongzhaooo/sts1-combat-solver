# 真值校准与夹具

状态：格式和运行器随四职业 M0 合入；第一批校准用例（四职业、A0 和 A20，共 24 个）和无头引擎参考适配器已发布。以 `tools/truth_calibration/` 为准。

本仓库不提供也不接收任何真值引擎。维护者的真值引擎派生自游戏本体，只在私有环境里使用。协作者用自己购买的游戏搭一个真值引擎，先用本仓库公开的校准用例把它校准，再用它给自己负责的机制生成原生夹具。公开 CI 只拿夹具检查 C++ 模拟器，不需要 Java，也不需要游戏。

## 两种数据

| | 校准用例 | 原生夹具 |
| --- | --- | --- |
| 用途 | 检查一个真值引擎是否与原版一致 | 检查 C++ 模拟器是否与真值一致 |
| 生成方 | 维护者的私有真值 | 任何已校准的真值（协作者或维护者） |
| 位置 | `fixtures/calibration/<职业>/` | `fixtures/native/<职业>/` |
| 起点 | 种子开局，从涅奥开始 | 一场战斗的开始状态 |
| 谁来跑 | 协作者对自己的真值跑 | 公开 CI 对模拟器跑；维护者合并前对私有真值复核 |

两者都只含游戏状态的数字和 ID，不含任何游戏代码、资源或文本。

## 用例格式（schema `sts1-calibration/1`）

```json
{
  "schema": "sts1-calibration/1",
  "id": "silent-a20-03",
  "game_jar_sha256": "<desktop-1.0.jar 的 sha256>",
  "character": "THE_SILENT",
  "ascension": 20,
  "seed": "6145041442760698968",
  "actions": [
    {"kind": "neow", "index": 1},
    {"kind": "map", "x": 3, "y": 0},
    {"kind": "play", "hand": 2, "card": "Neutralize", "target": 0},
    {"kind": "end"}
  ],
  "checkpoints": [
    {"after": 3, "observation": {"...": "见下"}}
  ],
  "source": {"truth": "maintainer", "truth_identity": "<真值构建身份>", "exporter": "<导出脚本提交>"}
}
```

- 用例文件是 `<id>.json` 或 gzip 压缩的 `<id>.json.gz`，运行器两种都读。
- `game_jar_sha256` 固定游戏版本，协作者用同一版本的游戏才有可比性。
- `actions` 是从开局起的完整动作序列。`play` 同时给手牌位置和牌的 Java ID，位置对不上就是失败，不去猜是哪张。
- `checkpoints` 指定第几个动作之后比较：`after` 是已执行的动作数，0 表示 `new` 返回的观测，必须递增且不超过动作数。战斗内每个动作后都设检查点；战斗外只在进出战斗和随机数计数变化的地方（奖励、商店、事件、地图选择等）设；最后一个动作之后总有检查点。
- `source` 里除了真值身份和导出脚本，维护者的用例还记录策略种子、楼层上限和结束原因（`end`），只作说明，不参与比较。

### 动作

字段除 `card` 是 Java 牌 ID 外都是整数；`[ ]` 里的字段可选。索引都从 0 开始，与无头引擎命令行一致，只有 `play` 的 `hand` 从 0 开始而命令行从 1 开始。`target` 和 `potion` 的目标是房间怪物组里的位置，包括已死的怪物。

| kind | 字段 | 含义 |
| --- | --- | --- |
| `neow`、`event`、`match` | `index` | 涅奥或事件的第几个选项；对对碰翻第几张 |
| `map` | `x`、`y` | 走到地图节点；Boss 是 `x=-1, y=15` |
| `play` | `hand`、`card`、[`target`] | 打出手牌 |
| `end` | | 结束回合 |
| `potion`、`discard_potion` | `slot`，`potion` 另有 [`target`] | 使用或丢弃药水 |
| `reward`、`open_card` | `index` | 领取奖励；打开卡牌奖励 |
| `pick` | `index`、`card` | 从卡牌奖励里拿牌，`card` 用来核对 |
| `skip`、`bowl`、`proceed` | | 跳过卡牌奖励；歌唱碗；继续 |
| `grid`、`grid_confirm` | `grid` 有 `index` | 网格选牌（升级、移除、变化、预见等）；确认 |
| `hand_select`、`hand_confirm` | `hand_select` 有 `index` | 从手牌选牌（弃牌、保留等）；确认 |
| `rest` | `index` | 篝火的第几个选项 |
| `chest`、`boss_relic`、`skip_boss_relic` | `boss_relic` 有 `index` | 开宝箱；拿或跳过 Boss 遗物 |
| `shop`、`buy_card`、`buy_relic`、`buy_potion`、`purge`、`leave` | 购买和移除有 `index` | 进商店、购买、移除、离开 |

### 观测

适配器的输出要严格按下面的字段，不多给也不少给：比较是对称的，多出来的字段也算差异。`run.py --check` 按这份清单检查用例的格式。

```text
phase                 无头引擎的决策阶段名：NEOW EVENT MAP PLAYER_TURN REWARD CARD_PICK CARD_SELECT
                      CAMPFIRE SHOP PROCEED CHEST BOSS_CHEST BOSS_RELIC GAME_OVER RUN_COMPLETE
floor                 楼层
turn                  战斗回合数（GameActionManager.turn），战斗外为 0
player                hp max_hp block energy gold
                      powers [{id, amount}]          能力，按游戏列表顺序
                      stance                         姿态 ID（Neutral Calm Wrath Divinity），战斗外为 Neutral
                      mantra                         Mantra 能力的层数，没有为 0
                      orbs [{id, passive, evoke}]    宝珠栏，空栏的 id 是 null（原版空栏的 ID 为 null）
                      orb_slots                      宝珠栏数，即 orbs 的长度
                      focus                          Focus 能力的层数，没有为 0
deck                  主牌组 [card]
piles                 hand draw discard exhaust，各为 [card]；战斗外为空
                      draw 按引擎列表顺序，最后一个是抽牌堆顶
card                  {id, upgrades, cost, retain}：Java ID、升级次数、本回合费用（costForTurn）、
                      单次保留位（AbstractCard.retain，不含牌自带的保留）
monsters              [{id, hp, max_hp, block, powers, intent, move}]，存活怪物，按怪物组顺序；
                      intent 是 Intent 枚举名，move 是 nextMove
relics                [{id, counter}]
potions               [id]，空栏是 "Potion Slot"
rng                   13 个种子随机数流的计数（Random.counter）：
                      ai card card_random event map merchant misc monster monster_hp potion relic shuffle treasure
```

战斗外 `block`、`energy` 为 0，`monsters` 为空。

模拟器快照（`BattleContext.from_snapshot` 的输入）在 M0 增加了下面的可选字段，缺省都按“没有”处理，旧快照照常导入：

- `character`：游戏的职业名 `IRONCLAD`、`THE_SILENT`、`DEFECT`、`WATCHER`，缺省为 `IRONCLAD`；其他值会被拒绝。
- `player.stance`：`NEUTRAL`、`CALM`、`WRATH` 或 `DIVINITY`。
- `player.mantra`：非负整数；快照的 `powers` 里也有 Mantra 时两者必须相等。
- `player.orb_slots`：整数，原版上限 10；更大的 mod 栏位拒绝导入。
- `player.orbs`：宝珠列表，长度等于栏位数，空栏的 `id` 为 null。每项 `{id, passive, evoke}`：`id` 是 CommunicationMod 的宝珠名（`Lightning`、`Frost`、`Dark`、`Plasma`，也接受引擎枚举名），`passive` 和 `evoke` 是游戏里已算过 Focus 的数值。没有原版卡牌会给宝珠加激发值，所以导入要求激发值恰好等于 `max(0, 基础激发值 + Focus)`，不等就拒绝（否则 Focus 为负时被截到 0 的数值会被反推成一个凭空的加成，Focus 回升后还留着）；黑暗珠的累计伤害存在 `evoke` 里。声明了 `orb_slots` 却没有 `orbs`、宝珠数超过栏位、或激发值与基础和 Focus 不符，都会被拒绝。
- 牌堆里的牌可带 `retain`（布尔，单次保留位）。

Focus 仍写在 `powers` 里。宝珠由 CommunicationMod 导出，生产端（`steam_mcts.build_snapshot`）原样透传；姿态和真言的单项格式等 M2 再定。

原生夹具使用同一份 `observation` 格式：一个战斗开始的观测加后续动作和检查点。M0 只让快照导入器接受可选的职业字段（见上）；让导入器直接读 `observation` 格式是后续步骤，尚未排期，届时协作者就不需要实现 Mod 的导出。

## 适配器协议

协作者的真值引擎外包一个命令行适配器：从标准输入逐行读 JSON 请求，逐行写 JSON 回复。

```text
→ {"op": "new", "seed": "4175070170808390440", "character": "THE_SILENT", "ascension": 20}
← {"ok": true, "observation": {...}}
→ {"op": "act", "action": {"kind": "play", "hand": 2, "card": "Neutralize", "target": 0}}
← {"ok": true, "observation": {...}}
→ {"op": "act", "action": {"kind": "play", "hand": 9, "card": "Shiv"}}
← {"ok": false, "error": "illegal action: hand index 9"}
→ {"op": "quit"}
```

每个 `new` 都要从相同的初始存档开始：游戏存档里的历史会改变之后的涅奥选项。维护者的用例用一份全部解锁（`unlockAll`）、没有任何历史的存档。

`python tools/truth_calibration/self_test.py` 用合成的假适配器、假命令行和合成用例（`tools/truth_calibration/selftest/`，不含任何游戏观测）检查运行器和参考适配器本身，包括故意制造的多字段、缺字段、值或类型不符、列表顺序和适配器退出。

## 参考适配器（无头引擎命令行）

`tools/truth_calibration/headless_adapter.py` 实现上面的协议，驱动一个 `cli.sh` 式的文本命令行（公开无头引擎 SlayTheSpireHeadlessEngine 的 `HeadlessCli` 就是这种）：

- 每个 `new` 起一个新的命令行进程，存档目录是新建的临时目录（`--cli` 里的 `{saves}`），可用 `--profile-template` 复制一份初始存档进去，或用 `--unlock-all` 在启动后发 `unlockAll`；
- 一个动作只有在命令行的 `actions` 列表里有完全相同的命令时才执行，执行用 `step <i>`；`play` 和 `pick` 先核对牌 ID；
- 每次回复的观测是命令行 `state` 经 `headless_projection.py` 投影的结果。投影只取 ID 和数字，名字、描述等游戏文本一概丢弃。

```bash
python tools/truth_calibration/run.py fixtures/calibration --adapter \
  "python tools/truth_calibration/headless_adapter.py --cli 'bash <引擎目录>/cli.sh <引擎目录>/data {saves}' --unlock-all"
```

命令行每条命令的输出以提示符 `> ` 结束；输出里出现以 `ERROR:`、`bad `、`usage:` 或 `unknown command` 开头的行，就算这个动作被拒绝。

### 需要引擎支持的字段（`state["calibration"]`）

无头引擎原样的 `state` 给不出全部观测字段。下面这些字段要由引擎补丁加在 `state` 的 `calibration` 对象里，缺了参考适配器会回复 `ok: false` 并指出缺的是哪一项，不会用默认值顶替：

```text
calibration.turn        GameActionManager.turn（战斗外 0）
calibration.stance      AbstractStance.ID（战斗外 "Neutral"）；原样的 state 只给本地化名字
calibration.deck        主牌组
calibration.hand / draw / discard / exhaust
                        各牌堆，每张 {id, upgrades, cost, retain}，含抽牌堆顺序；原样的 state 只给手牌，
                        也没有 retain，主牌组没有升级次数
calibration.monsters    [{intent, move}]，与 state.monsters 一一对应：Intent 枚举名（不受符文圆顶遮蔽）
                        和 nextMove
calibration.rng         {aiRng, cardRng, cardRandomRng, eventRng, mapRng, merchantRng, miscRng,
                        monsterRng, monsterHpRng, potionRng, relicRng, shuffleRng, treasureRng}，
                        AbstractDungeon 上各流的 Random.counter
```

其余字段（阶段、楼层、玩家数值、能力、宝珠、怪物数值和能力、遗物、药水）直接取自原样的 `state`。维护者在私有环境里用同样的投影出用例，并用这个参考适配器对私有真值重放了全部用例。

## 第一批用例覆盖什么

24 个用例：四个职业各 6 个，A0 和 A20 各 3 个。每个用例从新开局打到死亡或第 34 层前的地图选择；这批用例里所有局都在第 34 层前死亡，最远到第 29 层（铁甲 A0），A20 的局在第 3 到 14 层结束。共 5,894 个动作、4,890 个检查点，压缩后约 230 KB。

动作来自一个固定种子的简单策略：涅奥、事件、地图、篝火和 Boss 遗物按种子随机选；每次卡牌奖励都拿一张；按种子决定进不进商店、买不买；战斗里先打费用最高的可打出的牌，攻击打血最少的怪物，没有可打的牌就结束回合，血少时偶尔喝药水。策略很弱，所以覆盖的是第一、二幕的常见内容。

各职业覆盖到的机制（按用例数据统计）：

- 静默猎手：毒（6 个用例中 5 个给怪物上了毒）、小刀（4 个用例里出现 Shiv）、手牌弃牌选择（每个用例 12–24 次）、保留（Well Laid Plans）。
- 故障机器人：宝珠 Lightning、Frost、Dark、Plasma 的生成、被动和激发（满栏时生成新宝珠挤出最左边的）；Focus 正负变化（-4 到 4）；宝珠栏数变化（最多 9 栏）。
- 观者：Calm、Wrath、Divinity 三种姿态；真言累积（最高 9 层）；预见（Just Lucky、Foresight）；打出过自带保留的牌（Miracle、Protect、Crescendo、Insight、Windmill Strike 等）。
- 四个职业都有：事件、篝火、商店、药水使用、第一幕精英（Gremlin Nob、Lagavulin、Sentry），以及每次洗牌、抽牌和怪物意图的随机数计数。第一幕 Boss（Hexaghost、Slime Boss、The Guardian）只出现在铁甲和故障机器人的用例里。

没有覆盖：第三幕及以后、心脏、大多数稀有牌和遗物、单次保留位为真的检查点（这一位只在回合结束的保留过程中短暂为真）。

### 已知限制

- 维护者的真值把原版里少数不带种子的随机调用（`MathUtils.random` 和无种子的 `Collections.shuffle`，主要是某些随机选牌、随机选怪和回合结束时手牌触发的顺序）改成了一个额外的带种子流。导出时逐个动作检查这个流：除回合结束固定取的一次外，只要它被用到，用例就在那个动作之前截断。第一批用例里没有一个被截断。回合结束那一次只决定手牌回合结束效果（如灼伤、悔恨）的执行顺序，这批用例的结果与这个顺序无关。
- `game_jar_sha256` 是维护者本机 Steam 版 `desktop-1.0.jar` 的 sha256（`cfad868a…081673`）。真值派生自的上游无头引擎没有记录 JAR 哈希；能确认的是两者版本字符串都是 `[V2.3.4] (12-18-2022)`，且上游附带的 17 个英文本地化文件与这个 JAR 里的逐字节相同。
- `source.truth_identity` 是维护者真值运行时类路径的 sha256，包含绝对路径，只用来对应私有运行时，协作者不需要复现它。

## 校准流程

1. 准备正版游戏，核对 `desktop-1.0.jar` 的 sha256 与用例里的 `game_jar_sha256` 一致。
2. 用你自己的方式搭真值引擎（例如基于公开的无头引擎，或自己反编译自己的游戏）。怎么搭由你负责，引擎代码不要提交到本仓库。
3. 写适配器（基于无头引擎时可直接用上面的参考适配器，只需让 `state` 带上 `calibration` 扩展），然后跑：

   ```bash
   python tools/truth_calibration/run.py --adapter "<启动适配器的命令>" fixtures/calibration
   ```

   每个用例输出通过或失败；失败时给出第一个检查点和第一处差异的 JSON 路径（比较复用 `sim_patch/parity/core.py` 的严格比较）。
4. 全部用例逐项一致，才算校准通过，没有容差。随机数计数不一致，就算血量对上了也是失败。
5. 用校准过的真值给你负责的机制生成原生夹具，放进 `fixtures/native/<职业>/`，在 `source` 里写上你的 `truth_identity`（你的引擎版本的哈希，不是代码）。
6. 提 PR。维护者合并前用私有真值重放这些夹具，结果不一致时贴出第一处差异，双方一起定位。若问题出在你的真值，而现有校准用例没有覆盖到，维护者会补一个用例。

校准用例覆盖的机制有限。校准通过只说明你的真值在这些用例上与维护者一致，不说明它在新机制上一定对，所以合并前的私有复核不能省。

公开 CI 运行 `python tools/truth_calibration/run.py --check fixtures/calibration`，只检查每个已提交用例的格式和观测字段，不需要真值。

## 不要提交

- 任何真值引擎代码、反编译源码片段、游戏 JAR、资源、字体和本地化文本；
- 改过期望值、但不是由已校准真值生成的夹具；
- 为了让检查通过而放宽比较或加容差的改动。
