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

v0.6.1 supports SuperFastMode and fixes several automation stops. After a Discovery-style pick (Skill Potion and similar), the RNG prediction no longer depends on frame time, so it matches the game at any SuperFastMode speed; the Settings page shows the current multiplier and recommends at most x10. Automation no longer stops mid-turn after a generated-card pick, and the fast boss finish keeps Sundial, Nunchaku, Pen Nib and other cross-fight counters. Incense Burner follows a per-act counter rule (Act 1: 3, or 3-4 right after Gremlin Nob; Act 2: 5, 4 right after Slavers, 0 before Bronze Automaton once no elite is reachable; Act 3, Act 4 and every act transition: 4). At startup the solver names other mods that change the game's RNG. F6 exports add relic counters and fight context. Verified offline and in CI, not yet in long live runs.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
