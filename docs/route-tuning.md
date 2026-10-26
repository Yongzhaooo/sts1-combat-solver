---
title: 战士全自动爬塔 · 路线调参数据包
subtitle: 110 局 A20 实测路线 · 能回答什么、不能回答什么
lang: zh-CN
---

# 这包是什么

`tools/route_history/data/ironclad-a20.jsonl` 是一批真实对局的路线记录，配套两个脚本：

| | 用途 |
| --- | --- |
| `tools/route_history/export_runs.py` | 把游戏存档目录里每个 `.run` 转成 jsonl 里的一行 |
| `tools/route_history/report.py` | 按幕统计这批路线（房型均值、分布、进幕血量和金币、营火选择），可选用权重表并排 |

这份数据是读 `overlay/resources/route-preferences.json` 时的参考：它记录的是**当前这套策略实际走过的路**。
它不能替你算出更好的权重——原因见下面「没有地图」一节，但先看清样本性质很重要。

# 数据从哪来

- 来源：本机游戏存档 `runs/2_IRONCLAD/`（战士，2 号存档位），110 个 `.run`，全部纳入。
- 全部战士、全部**进阶 20**、**108 胜 2 负**、44–57 层、日期 2026-10-16 ~ 2026-10-26。
- 110 局里有 1 个种子被玩了两遍（两局都是败局、时长不同），所以不同种子只有 109 个；**别拿 `seed` 当唯一键**。
- 这些局是**本 mod 自动爬塔跑出来的**，也就是同一个 `RoutePlanner` 加同一份权重走出来的 110 条路。
  所以它是这套策略的「现状」，不是「更优解」：拿它调权重容易把现状当目标。
- 这批局来自一台机器的 2 号存档位；换存档位或换机器就没有它们。想把样本做大，只能多跑、重新导出。

# 没有地图，所以能回答什么

存档里的 `path_per_floor` 只记**走过的房型序列**（`M` 普通怪、`E` 精英、`R` 营火、`?` 问号、`$` 商店、`T` 宝箱、`B` Boss），
不记地图拓扑，也不记 x/y 坐标。`RoutePlanner` 的打分是在地图上枚举出来的（`overlay/src/sts1solver/RoutePlanner.java`），
因此这份数据缺了打分需要的那一半：**同一行上它没走的那两个邻居**。

于是：

- **能答**：一代里实际有多少精英/营火/商店、进下一幕时血量和金币多少、营火都拿去干什么了、权重和现状差多少。
- **不能答**：权重该改多少。没有反事实，就没有可拟合的目标；任何「按这包数据自动调权重」的做法都是在拟合现状。
- 想真正回放打分，需要带 x/y 的地图导出（mod 的 F7 地图导出有坐标），那需要另行设计，本包不含。

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
- **`issues` 只标记、不过滤**：`short_act`（1–3 幕不足 12 格，即不是标准 16 格的局）、
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
runs 110: 108 won, 2 lost; floors 44-57; 2026-10-16 to 2026-10-26
acts per run: 3 acts x2, 4 acts x108

act  runs  rooms  M     E     R     ?     $     T     B
1    110   16.0   5.23  2.17  3.23  2.02  1.23  1.13  1.00
2    110   16.0   4.37  2.04  3.04  2.55  1.75  1.26  1.00
3    110   16.9   4.66  1.87  2.74  2.60  1.65  1.35  1.98
4    108   4.0    0.00  1.00  1.00  0.00  1.00  0.00  1.00

## state entering each act
act  runs  hp   p25  p50  p75   gold
2    110   90%  86%  89%  95%   262
3    110   92%  87%  92%  99%   273
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
- `--character` 可强制指定职业（默认从文件里读），换存档目录时用来兜底。

# 隐私

导出的记录**不含** `play_id`、`timestamp` 和完整的 `local_time`（只保留到天的 `date`），也不含任何本地路径或账号标识；
保留 `seed`/`score`/`playtime_seconds`/`build` 是为了能复现和对比。分享前仍建议自己看一遍明文
（与 `PRIVACY.md` 的口径一致：过滤不等于保证匿名）。
