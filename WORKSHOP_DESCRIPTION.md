# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.

Ironclad combat solver with step, turn and automatic execution.

- Independent automatic potion use and post-combat potion replacement.
- Plans around relic counters, including Happy Flower, Incense Burner, Pen Nib, Nunchaku, Sundial and Ink Bottle.
- Plans Hand of Greed and Feed kills for gold and permanent max HP.
- Base search budgets per round: Fast 2,000 / Standard 8,000 / Deep 128,000. Standard can deepen when needed.

**Full autopilot (early): this is an early training result and its play is not good yet. On a small dataset it wins roughly 59%–81% of A20 runs.** It upgrades too rarely, lacks a deckbuilding plan and walks into early elites at full HP. Choose a mode when a run starts: AI autopilot, Combat auto (fights only) or Manual (step by step: the solver and the network suggest, Enter or a click runs each step); F9 stops and returns to Manual. A small network picks Neow, rewards, campfires, shops and events; combat uses the solver. Removal only targets curses, Strikes, Defends and Bash.

Subscribe with ModTheSpire and BaseMod. Windows and both Mac architectures include their runtime. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG. Sizing, language, foresight, recording and Boss fast finish (off by default) live on the panel's Settings page. Finite search does not guarantee optimal play; foresight reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.6.2 includes everything in v0.6.1, which was never published separately, plus autopilot rule fixes. Act 3's first map choice is now planned (it had no route data); Neow and act-start maps are encoded as in the training simulator; after skipping a Boss relic the bot leaves the chest instead of reopening it in a loop; acts 1-2 take the relic instead of the Sapphire Key; out of combat only Fruit Juice and Entropic Brew are used; Knowing Skull checks HP after paying; Bonfire Spirits is the network's pick over all legal cards; Defends rank before other attacks in the removal order; routes require keys only in the final act, lean to the burning elite in acts 1-2 when strong, count expected fight gold before shops, and no longer spend a Wing Boots charge on a tie; Gambler's Brew and Blessing of the Forge potion-scoring IDs fixed. Foresight (bright-eye) mode now defaults to off for new installs and configs that never saved settings; saved settings are unchanged, and the Settings page toggles it. While off, event-fight gold and relic previews are hidden. Autopilot reads hidden event information only for five events whose result a save-and-load reveals (Knowing Skull, Scrap Ooze, Dead Adventurer, Face Trader, The Joust) and records what it read in the decision note. From v0.6.1: Supports SuperFastMode and fixes several automation stops. After a Discovery-style pick (Skill Potion and similar), the RNG prediction no longer depends on frame time, so it matches the game at any SuperFastMode speed; the Settings page shows the current multiplier and recommends at most x10. Automation no longer stops mid-turn after a generated-card pick, and the fast boss finish keeps Sundial, Nunchaku, Pen Nib and other cross-fight counters. Incense Burner follows a per-act counter rule (Act 1: 3, or 3-4 right after Gremlin Nob; Act 2: 5, 4 right after Slavers, 0 before Bronze Automaton once no elite is reachable; Act 3, Act 4 and every act transition: 4). At startup the solver names other mods that change the game's RNG. F6 exports add relic counters and fight context. Verified offline and in CI, not yet in long live runs.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
