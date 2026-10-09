# STS1 Combat Solver — Ironclad

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Opt in when a run starts (full auto, step-by-step or manual); F9 takes over at any time. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter/F10: auto; F9: stop; F8: collapse. Sizing, language and recording live on the panel's Settings page. Finite search does not guarantee optimal play; foresight reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.4.2 replaces map selection with reviewed route rules, plans every connected map, replans after state changes and requires the burning elite when reachable and the green key is still missing in Act 3. Combat and other decisions are unchanged.
