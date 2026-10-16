# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Choose a mode when a run starts: AI autopilot, Combat auto (fights only) or Manual (step by step: the solver and the network suggest, Enter or a click runs each step); F9 stops and returns to Manual. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG. Sizing, language, foresight, recording and Boss fast finish (off by default) live on the panel's Settings page. Finite search does not guarantee optimal play; foresight reveals hidden RNG; Glowing Eye (off by default) covers map rooms ahead, event-fight rewards and Neow's rolled blessings.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.7.0: the combat solver now plays small-deck loops. Without potions it also searches lines that cycle cards through draw and energy refunds (Awakened One, Time Eater, Bronze Automaton and other long fights). On 562 real Boss positions deaths fell from 55 to 46 and won fights kept about 2 more HP; killing the Awakened One's first phase is no longer scored as a setback. Mods → this mod → Config adds four switches: Boss fast finish, Feed mode (max HP gained by Feed counts double; off by default), test mode (every card played, potion used and turn ended this run, with the state at the time, written to sts1-test-SEED.jsonl on the desktop; latest run only; off by default) and route marks (the planned route on the map; on by default). Neow's rolled blessings are shown only with Glowing Eye on. Fixed a cancel request occasionally lost right after a search started. Thanks to 七月流火 for testing and feedback. Earlier changes are in the GitHub Releases.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
