# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.
If you find it useful, please star the repository on [GitHub](https://github.com/Yongzhaooo/sts1-combat-solver).

You build the deck and pick the path; it fights. Step, turn and automatic execution for the Ironclad and colorless cards.

- Automatic potion use in fights and post-combat potion swaps.
- Plans around relic counters (Happy Flower, Incense Burner, Pen Nib and more) and Hand of Greed and Feed kills.
- Search effort is set by act and room. On older PCs, High speed in Settings searches 1/20 as much: faster, with a lower win rate and more HP lost; Streak keeps the full search in acts 1-3: slower.

![Combat panel](https://raw.githubusercontent.com/Yongzhaooo/sts1-combat-solver/main/docs/images/panel-en.png)

**Full autopilot (early):** choose Standby, Combat auto or AI autopilot when a run starts, and switch on the panel at any time; F9 returns to Standby. In Standby the panel stays folded, a fight is searched only after you press Solve, and when several potion plans are usable nothing plays until you pick one. The autopilot's network and reviewed rules win 73.1% of 2,442 A20 three-key Heart runs in training (71.0% of 1,014 with v0.9 search); it upgrades too rarely and lacks a deckbuilding plan.

**Let an AI agent play:** point Claude Code, Codex or a similar agent at the agent-play branch of the GitHub repository and have it follow AGENT_PLAY.md: "Read https://github.com/Yongzhaooo/sts1-combat-solver/blob/agent-play/AGENT_PLAY.md, follow it to check and configure my Slay the Spire mods (including incompatible mods), then climb the Spire with the solver."

Subscribe with ModTheSpire and BaseMod. Windows x64 and Apple Silicon Macs; Intel Macs are not supported. F10: play or pause this fight; F9: stop; F8: collapse; F7: map PNG; F6 or Export log in the header: diagnostic log (local only). Finite search does not guarantee optimal play and trails top players on Burning Pact ordering, Snecko Oil and some small loops. Mods that change combat or RNG, such as SaveStateMod and Undo, are not compatible. Glowing Eye (off by default) reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.9.6: Reduces unproductive loops during Intangible and fixes repeated plays while the Awakened One waits to revive. Equal results prefer shorter lines. Search stops expanding at its memory limit and keeps the best line found. Potion plans now show why they cannot be selected, and F6 logs include the first solve state and potion-branch details. Some unresponsive-solver and Weak Potion reports still need new logs for diagnosis. Thanks to 七月流火（猫火）, 暖暖二号机 and the community for testing and feedback.

Special thanks to 七月流火（猫火） for long-term testing and feedback.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
