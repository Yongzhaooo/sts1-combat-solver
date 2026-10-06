# 药水奖励评分与使用规则

本文记录 0.2.1 版战士求解器的药水奖励策略。分数用于比较战后药水库存，是人工设定的相对保留价值；它不是预计回血量，也不是战斗内的固定喝药顺序。实际规则以 `overlay/src/sts1solver/PotionRewards.java` 为准。

## 基础分

| 药水 ID | 分数 | 条件 |
| --- | ---: | --- |
| `FairyPotion` | 70 / 0 | 有绽放印记时 0 |
| `Fruit Juice` | 60 | 自动换药开启时，领取后在可使用状态下直接喝掉 |
| `GhostInAJar` | 60 |  |
| `DuplicationPotion` | 40 / 32 | 牌组含燔祭、燎原、壁垒、恶魔形态或突破极限时 40 |
| `EntropicBrew` | 40 |  |
| `DistilledChaos`（精炼混沌） | 36 |  |
| `HeartOfIron` | 36 |  |
| `Swift Potion`（迅捷） | 34 |  |
| `Gambler's Brew`（赌徒特酿） | 34 |  |
| `CultistPotion` | 34 |  |
| `BloodPotion` | 32 / 0 | 有绽放印记时 0 |
| `LiquidMemories` | 30 |  |
| `PowerPotion` | 28 |  |
| `Regen Potion` | 28 / 0 | 有绽放印记时 0 |
| `SpeedPotion` | 28 / 12 | 有橙色药丸时 28，否则 12 |
| `Ancient Potion`（人工制品） | 26 / 12 | 第二幕 26，其他幕 12 |
| `SteroidPotion` | 25 / 10 | 牌组有燔祭、旋风斩、连续拳或飞剑回旋镖时 25，否则 10 |
| `Strength Potion` | 28 / 22 | 同上，有上述任意牌时 28，否则 22 |
| `Dexterity Potion` | 24 |  |
| `FearPotion` | 24 |  |
| `SkillPotion` | 23 |  |
| `ColorlessPotion` | 23 |  |
| `Weak Potion` | 22 |  |
| `SneckoOil` | 22 |  |
| `Fire Potion` | 20 / 14 | 第一幕 20，之后 14 |
| `Energy Potion` | 20 |  |
| `AttackPotion` | 19 |  |
| `Explosive Potion` | 18 |  |
| `ElixirPotion` | 18 |  |
| `Block Potion` | 16 |  |
| `LiquidBronze` | 16 |  |
| `EssenceOfSteel`（甲壳） | 12 |  |
| `SmokeBomb` | 12 |  |

## 换药怎么算

药栏有空位时优先领取。药栏已满时，程序逐个尝试替换可丢弃的旧药，比较替换前后的**整栏总分**；新库存至少高 3 分才会自动换药。添水会阻止领取。未知或模组药水返回 `-1`，满栏时交给玩家自行比较。

若牌组尚未具备代码所识别的稳定上限，同时持有迅捷或赌徒特酿，以及至少一瓶指定的找牌／成长药水，库存另加 12 分。后者包括能力、技能、攻击、无色、混沌、蛇眼、教徒和力量药水。精炼混沌有自己的 36 分基础分，目前不参与这项组合加分。两瓶迅捷或赌徒特酿不会因为功能相近而被额外扣分。

## 战斗内喝药

战斗求解器先搜索不用药路线，再比较单瓶及必要时的多瓶路线。自动推荐会考虑获胜、等效战损、药水保留价值、近期精英或首领，以及尾巴和小精灵的消耗。它只在已找到且回放验证的路线之间选择；搜索预算有限，不能保证全局最优。战后表中的 34 分、36 分等不直接换算成战斗里的血量。
