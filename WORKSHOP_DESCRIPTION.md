# STS1 Combat Solver — Ironclad

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Opt in when a run starts (full auto, step-by-step or manual); F9 takes over at any time. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter/F10: auto; F9: stop; F8: collapse. Sizing, language and recording live on the panel's Settings page. Finite search does not guarantee optimal play; foresight reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.4.3 fixes Headbutt and Armaments selection, Empty Cage multi-selection and action timeouts while the map pauses combat. Combat auto and full AI auto have separate controls. Rewards collect relics before cards. Reviewed event rules and contextual relic scores improve outside-combat choices; the final known reachable Act 3 chest prioritizes a missing sapphire key. Survival, draw and power potions gain priority late in Act 3 through the Act 4 elite. Includes backend recovery and per-seed local diagnostics: recording is on by default and can be disabled; F6 exports one JSON to the desktop without uploading. Voluntary experience recording remains off by default. Offline regressions passed; complete runs and multi-mod combinations still need game testing.
