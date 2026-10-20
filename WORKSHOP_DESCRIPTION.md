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

v0.8.0-beta.3: Fixed autoplay stopping at the card picker when a hand selection such as Burning Pact is the route's last step. In the Heart fight the search no longer keeps looping past the 200-damage cap until it runs out of HP, so it can find wins that need to end the turn. Corruption costs for Enlightenment+ and Secret Technique now match the game. Some Heart fights that rely on retries and Gambler's Brew may still be judged lost. Burning Pact mid-selection import and selection changes remain deferred. Thanks to 七月流火（七月火）.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
