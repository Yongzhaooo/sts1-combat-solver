# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Opt in when a run starts (full auto, step-by-step or manual); F9 takes over at any time. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter/F10: auto; F9: stop; F8: collapse. Sizing, language and recording live on the panel's Settings page. Finite search does not guarantee optimal play; foresight reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.6.0 brings the combat simulation closer to the original game, using fixes checked against the decompiled Java in our research base: Spire Shield/Spear move order; Snecko and Guardian apply Weak before Vulnerable (one Artifact now blocks the right debuff); Havoc or other autoplayed cards aimed at a reviving Awakened One or a fleeing monster count as played and fade; Corruption cost updates on every exhaust and with Snecko Oil; Red Skull Strength in the game's power order (Pen Nib/Vigor damage); Mind Blast played by Havoc; Smoke Bomb escapes keep end-of-combat healing; Malleable/Reactive, Reptomancer/Collector/Automaton and Gremlin Leader deaths; identical cards in hand selections (Dual Wield + Duplication) no longer stall. Cards and relics the solver has not implemented are now reported before searching instead of failing mid-search. These fixes passed offline replays against the Java game and CI; they have not yet been tested in long live runs.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
