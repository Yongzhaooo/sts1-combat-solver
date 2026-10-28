---
title: 战士全自动爬塔 · 路线调参数据包
subtitle: 109 局 A20 实测路线 · 能回答什么、不能回答什么
lang: zh-CN
---

# 这包是什么

`tools/route_history/data/ironclad-a20.jsonl` 是一批真实对局的路线记录，配套两个脚本：

| | 用途 |
| --- | --- |
| `tools/route_history/export_runs.py` | 把游戏存档目录里每个 `.run` 转成 jsonl 里的一行 |
| `tools/route_history/report.py` | 按幕统计这批路线（房型均值、分布、进幕血量和金币、营火选择），可选用权重表并排 |

这份数据是读 `overlay/resources/route-preferences.json` 时的参考：它记录的是**当前这套策略实际走过的路**。
它不能替你算出更好的权重——原因见下面「存档不记地图」一节，但先看清样本性质很重要。

# 数据从哪来

- 来源：本机游戏存档 `runs/2_IRONCLAD/`（战士，2 号存档位），110 个 `.run`，全部纳入。
- 全部战士、全部**进阶 20**、**108 胜 2 负**、44–57 层、日期 2026-10-16 ~ 2026-10-26。
- 种子 `2371126309169301079` 的两条败局记录，除 `playtime_seconds` 外每层的房间、血量、金币都相同，是同一局被记了两次（比如读档重打），所以不同的局只有 109 个。`report.py` 按 (`seed`, 各幕 `rooms`) 去重，jsonl 里仍保留两行。
- 这些局是**本 mod 自动爬塔跑出来的**，也就是同一个 `RoutePlanner` 加同一份权重走出来的 110 条路。
  所以它是这套策略的「现状」，不是「更优解」：拿它调权重容易把现状当目标。
- 这批局来自一台机器的 2 号存档位；换存档位或换机器就没有它们。想把样本做大，只能多跑、重新导出。

# 存档不记地图，但地图可以由种子重建

存档里的 `path_per_floor` 只记**走过的房型序列**（`M` 普通怪、`E` 精英、`R` 营火、`?` 问号、`$` 商店、`T` 宝箱、`B` Boss），
不记地图拓扑，也不记 x/y 坐标。`RoutePlanner` 的打分是在地图上枚举出来的（`overlay/src/sts1solver/RoutePlanner.java`），
因此 jsonl 本身缺了打分需要的那一半：**同一行上它没走的那两个邻居**。

这一半可以补回来，不需要 F7 地图导出：地图由种子决定。引擎有 `Map::fromSeed(seed, ascension, act, burning)`，
Python 绑定暴露为 `slaythespire.SpireMap(seed, asc, act, bool)`；各幕的种子偏移是第 1 幕 +1、第 2 幕 +200、第 3 幕 +600，与游戏一致。
包里已有 `seed` 和 `ascension`，所以原则上可以逐幕生成地图，枚举与 `rooms` 相容的路径（地图上的 `?` 节点可以对应 `M`/`$`/`T`/`?`），
得到没走的兄弟节点。要留意：负数种子按补码转成 uint64；同一房型序列可能对应多条路径，要标出有歧义的局；
第 4 幕是固定地图；燃烧精英需要 `burning` 为真；先拿几局核对重建出的地图和游戏一致再信它。
`report.py` 目前**没有**实现这一步，下面「能答 / 不能答」说的是只用这份 jsonl 时的边界。

于是：

- **能答**：一代里实际有多少精英/营火/商店、进下一幕时血量和金币多少、营火都拿去干什么了、权重和现状差多少。
- **不能答**：权重该改多少。只用这份 jsonl 没有反事实，就没有可拟合的目标；任何「按这包数据自动调权重」的做法都是在拟合现状。
- 想真正回放打分，先按上面的方法由种子重建地图，本包不含这一步。

改权重仍然要靠人的判断 + 实机 A/B，这份数据的作用是把「现状」量出来，让改动的起点不是猜的。

# 文件与字段

`data/ironclad-a20.jsonl` 一行一局，`schema` 固定为 `sts1-route-history/1`：

| 字段 | 含义 |
| --- | --- |
| `character` / `ascension` | 职业、进阶（这里恒为 `IRONCLAD` / 20） |
| `victory` / `floor_reached` | 是否通关、到达的层数（含幕间的分隔行） |
| `seed` / `date` / `build` | 种子、开局日期（只到天）、游戏版本 |
| `score` / `playtime_seconds` | 分数、时长 |
| `acts[]` | 每幕：`act`、`rooms`（房型字符串）、`hp[]`、`max_hp[]`、`gold[]`、`entry` |
| `campfires[]` | 每次营火：`floor`、`key`（`SMITH`/`REST`/`RECALL`/`DIG`/`PURGE`/`LIFT`） |
| `issues[]` | 导出时发现的可疑点，见下 |

字段口径（读数据前先看这几条）：

- **幕之间有一行分隔**：`path_per_floor` 里两幕之间是 `null`，它计入 `floor_reached` 但不是房间，
  所以第 1 幕第 16 层打完 Boss，第 2 幕从第 18 层开始。A20 第 3 幕末尾有两行 `B`（双 Boss），正常。
- **`hp`/`max_hp`/`gold` 按层对齐**：索引 = 层号 − 1，记的是**该层结算后**的值。
  存档里这三个数组常比 `path_per_floor` 少一项，只是末尾那个 `null` 分隔行没有对应记录，房间层都齐全。
- **`entry` 取的是幕后分隔那一行**，也就是下一幕开打前的血量/金币。它比 Boss 战结束时高，
  因为游戏自己在 Boss 后回血（这批局都是 A20：补缺失血量的 75%，例：31/75 → 64/75），不是 mod 的行为；
  `entry` 里出现 `null` 表示存档没记到这一行。
- **`issues` 在 jsonl 里只标记、不过滤**（`report.py` 的各幕房型均值会跳过被标 `short_act` 的那一幕，例如种子 `56088640055118359` 第 3 幕只有 `M?BB`，是秘密传送门）：`short_act`（1–3 幕不足 12 格，即不是标准 16 格的局）、
  `odd_boss`（Boss 行异常）、`path_taken_differs`（存档自带的 `path_taken` 与 `path_per_floor` 不一致，
  存档普遍如此，`path_taken` 会把 `M`/`$`/`T` 写成 `?`，一律以 `path_per_floor` 为准）。

# 用法

```bash
# 现状基线
python tools/route_history/report.py

# 只看向某一幕
python tools/route_history/report.py --act 2

# 把权重表和现状并排（改权重前先跑这个）
python tools/route_history/report.py --weights overlay/resources/route-preferences.json
```

当前数据跑出来的几行（写这份文档时）：

```
runs 109: 108 won, 1 lost; floors 44-57; 2026-10-16 to 2026-10-26
acts per run: 3 acts x1, 4 acts x108

act  runs  rooms  M     E     R     ?     $     T     B
1    109   16.0   5.21  2.17  3.23  2.03  1.23  1.13  1.00
2    109   16.0   4.36  2.04  3.04  2.55  1.75  1.27  1.00
3    108   17.0   4.69  1.90  2.77  2.61  1.66  1.37  1.99
4    108   4.0    0.00  1.00  1.00  0.00  1.00  0.00  1.00

## state entering each act
act  runs  hp   p25  p50  p75   gold
2    109   90%  86%  89%  94%   263
3    109   92%  87%  92%  99%   271
4    108   94%  91%  96%  100%  261
```

`--weights` 那一段把 `1:balanced`/`2:balanced`/`3:balanced` 的每一项和观测均值放在一起，
注意 `observed` 只有 `elite`/`fire`/`event`/`monster`/`shop` 有值（对应 `E`/`R`/`?`/`M`/`$` 的每局均值），
其它项（`prepare`/`pre_fire`/`early_shop`/`late_shop`/`close_shops`/`low_hp_elites`）在存档里没有可对照的量，
显示为 `-`——它们依赖每一行的血量和卡组状态，而存档不记这些。

# 改权重的顺序

1. 跑 `report.py --weights ...`，先知道现状：一代 2 个左右精英、3 个左右营火、1–2 个商店。
2. 只改 `overlay/resources/route-preferences.json` 里的 `N:balanced` 三组（`RoutePlanner` 只用这三组，
   `:upgrade`/`:removal` 是别的模式在用，别顺手改）。
3. 用 `overlay/test/RoutePlannerCheck.java` 验证地图枚举的不变量没被破坏。
4. 实机跑，用「每局精英数/进幕血量/胜率」对比改动前后；不要拿这包里的现状当验收标准。

# 重新导出别的存档

```bash
python tools/route_history/export_runs.py \
    --runs-dir "<Slay the Spire>/runs/2_IRONCLAD" \
    --out tools/route_history/data/ironclad-a20.jsonl
```

- 存档是明文 JSON，编码不是 UTF-8：脚本按 `utf-8-sig → utf-8 → gbk → cp1252` 依次尝试（这 110 个文件全是 GBK）。
- 目录里每个 `.run` 都会解析；不满足不变量（`len(path_per_floor) == floor_reached`、房型字符合法、
  房间数 + 幕间分隔行 == `len(path_per_floor)`）的文件会被拒绝并打印原因，不会写进包。
- `--character` 是过滤条件：只保留 `character_chosen` 等于它的局（如 `IRONCLAD`），其他职业的局被跳过；不会改写记录里的职业。

# 隐私

导出的记录**不含** `play_id`、`timestamp` 和完整的 `local_time`（只保留到天的 `date`），也不含任何本地路径或账号标识；
保留 `seed`/`score`/`playtime_seconds`/`build` 是为了能复现和对比。分享前仍建议自己看一遍明文
（与 `PRIVACY.md` 的口径一致：过滤不等于保证匿名）。
