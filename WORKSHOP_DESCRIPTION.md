# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Search effort is set automatically by act and room. For older PCs, High-speed mode on the panel runs every fight at 1/20 of the search: faster, with a lower win rate and more HP lost.

**Full autopilot (early): this is an early training result and its play is not good yet. In training it wins 73.1% of 2,442 A20 three-key Heart runs (71.0% of 1,014 with v0.9 search).** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Choose a mode when a run starts: AI autopilot, Combat auto (fights only) or Manual (step by step: the solver and the network suggest, Enter or a click runs each step); F9 stops and returns to Manual. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows x64 and Apple Silicon Macs include their runtime; Intel Macs are not supported. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG. Sizing, language, foresight, recording and Boss fast finish (off by default) live on the panel's Settings page; Feed mode, test mode and route marks are in Mods → Config. Finite search does not guarantee optimal play and still trails top players on Burning Pact ordering, Snecko Eye and some small loops; foresight reveals hidden RNG; Glowing Eye (off by default) covers map rooms ahead, event-fight rewards and Neow's rolled blessings.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.9.0: Much faster search with a slightly lower win rate and HP saved. Search effort is now set automatically by act and room, with a small network guiding normal fights in acts 1-3; in 259 same-seed whole-run pairs it takes 0.51× the time and wins the Heart 6.2 points less often. The three search presets are gone; the new High-speed mode for older PCs warns about weaker play when turned on. Macs: Apple Silicon only; Intel Macs should stay on v0.8.0-beta.3. Fixed: the solver expecting a Bronze Orb's last taken card back, Warped Tongs and end-of-turn relic order after Toolbox, Forethought+ multi-pick confirm, Havoc-played unplayable cards not exhausting, and the shop's card removal being used up and charged on entry after a reload (a vanilla issue). Playing by hand with the solver, 七月流火（猫火） recently reached a streak of about 80 wins (SL-equivalent) and is close to 100. Thanks to 七月流火（猫火）.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
