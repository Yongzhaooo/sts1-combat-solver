# 真值校准与夹具

状态：提议（proposed）。格式和工具在四职业 M0 里实现，落地前字段还可能调整；以合入后的 `tools/truth_calibration/` 为准。

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
  "id": "silent-0003",
  "game_jar_sha256": "<desktop-1.0.jar 的 sha256>",
  "character": "THE_SILENT",
  "ascension": 20,
  "seed": "4175070170808390440",
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

- `game_jar_sha256` 固定游戏版本，协作者用同一版本的游戏才有可比性。
- `actions` 是从开局起的完整动作序列。`play` 同时给手牌位置和牌的 Java ID，位置对不上就是失败，不去猜是哪张。
- `checkpoints` 指定第几个动作之后比较：`after` 是已执行的动作数，0 表示 `new` 返回的观测，必须递增且不超过动作数。战斗内每个动作后都设检查点；战斗外只在进出战斗、奖励和商店这类会消耗随机数的地方设。
- `observation` 字段（M0 定稿）：
  - 阶段、楼层、回合数；
  - 玩家：血量、最大血量、格挡、能量、金币、能力（ID 和层数）、姿态、真言、宝珠（ID、被动值、激发值）、宝珠栏数、Focus；
  - 牌堆：手牌、抽牌堆（含顺序）、弃牌堆、消耗堆，每张牌给 Java ID、升级次数、当前费用、是否保留；
  - 怪物：ID、血量、最大血量、格挡、能力、意图、招式 ID；
  - 遗物（ID 和计数器）、药水；
  - 各随机数流的计数：ai、card_random、misc、monster_hp、potion、shuffle 等。

模拟器快照（`BattleContext.from_snapshot` 的输入）在 M0 增加了下面的可选字段，缺省都按“没有”处理，旧快照照常导入：

- `character`：游戏的职业名 `IRONCLAD`、`THE_SILENT`、`DEFECT`、`WATCHER`，缺省为 `IRONCLAD`；其他值会被拒绝。
- `player.stance`：`NEUTRAL`、`CALM`、`WRATH` 或 `DIVINITY`。
- `player.mantra`：非负整数；快照的 `powers` 里也有 Mantra 时两者必须相等。
- `player.orb_slots`：整数。`player.orbs`：宝珠列表。模拟器还没有宝珠状态，非空列表会被拒绝，而不是被忽略。
- 牌堆里的牌可带 `retain`（布尔，单次保留位）。

Focus 仍写在 `powers` 里。Mod 目前不导出这些字段，生产端现在只写 `character`；宝珠和姿态的单项格式（ID、被动值、激发值）等 M2、M3 导出时再定。

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

适配器的输出要严格按 `observation` 的字段，不多给也不少给：比较是对称的，多出来的字段也算差异。

`python tools/truth_calibration/self_test.py` 用一个合成的假适配器和一个合成用例（`tools/truth_calibration/selftest/`，不含任何游戏观测）检查运行器本身，包括故意制造的多字段、缺字段、值或类型不符、列表顺序和适配器退出。

## 校准流程

1. 准备正版游戏，核对 `desktop-1.0.jar` 的 sha256 与用例里的 `game_jar_sha256` 一致。
2. 用你自己的方式搭真值引擎（例如基于公开的无头引擎，或自己反编译自己的游戏）。怎么搭由你负责，引擎代码不要提交到本仓库。
3. 写适配器，然后跑：

   ```bash
   python tools/truth_calibration/run.py --adapter "<启动适配器的命令>" fixtures/calibration
   ```

   每个用例输出通过或失败；失败时给出第一个检查点和第一处差异的 JSON 路径（比较复用 `sim_patch/parity/core.py` 的严格比较）。
4. 全部用例逐项一致，才算校准通过，没有容差。随机数计数不一致，就算血量对上了也是失败。
5. 用校准过的真值给你负责的机制生成原生夹具，放进 `fixtures/native/<职业>/`，在 `source` 里写上你的 `truth_identity`（你的引擎版本的哈希，不是代码）。
6. 提 PR。维护者合并前用私有真值重放这些夹具，结果不一致时贴出第一处差异，双方一起定位。若问题出在你的真值，而现有校准用例没有覆盖到，维护者会补一个用例。

校准用例覆盖的机制有限。校准通过只说明你的真值在这些用例上与维护者一致，不说明它在新机制上一定对，所以合并前的私有复核不能省。

## 不要提交

- 任何真值引擎代码、反编译源码片段、游戏 JAR、资源、字体和本地化文本；
- 改过期望值、但不是由已校准真值生成的夹具；
- 为了让检查通过而放宽比较或加容差的改动。
