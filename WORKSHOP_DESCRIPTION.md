# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Choose a mode when a run starts: AI autopilot, Combat auto (fights only) or Manual (step by step: the solver and the network suggest, Enter or a click runs each step); F9 stops and returns to Manual. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG. Sizing, language, foresight, recording and Boss fast finish (off by default) live on the panel's Settings page; Feed mode, test mode and route marks are in Mods → Config. Finite search does not guarantee optimal play; foresight reveals hidden RNG; Glowing Eye (off by default) covers map rooms ahead, event-fight rewards and Neow's rolled blessings.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.8.0-beta.1: Adopts the π 2.6 combat search core, improves finite loops and state grouping, and removes redundant actions while preserving victory, HP, growth and protected resources. F6 includes player actions from the last five fights by default. Fixes runaway search, Gremlin role identification and several selection/action-resolution cases. Shared search is separated from the Mod adapter, with one runtime file inventory for all platforms. Burning Pact mid-selection import and reselection ordering are deferred until Silent solver work. This is a beta. Thanks to 七月流火 (chat name 七月火), ૭٩奶油糖果, 幼猫（浪花） for examples and validation feedback, and 严肃研究鸡煲打法中 for selection and loop ideas.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
