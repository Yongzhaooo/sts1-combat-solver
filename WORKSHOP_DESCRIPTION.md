# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.
If you find it useful, please star the repository on [GitHub](https://github.com/Yongzhaooo/sts1-combat-solver).

You build the deck and pick the path; it fights. Step, turn and automatic execution for the Ironclad and colorless cards.

- Automatic potion use in fights and post-combat potion swaps.
- Plans around relic counters (Happy Flower, Incense Burner, Pen Nib and more) and Hand of Greed and Feed kills.
- Search effort is set by act and room. On older PCs, High-speed mode on the panel searches 1/20 as much: faster, with a lower win rate and more HP lost.

**Full autopilot (early):** choose Manual, Combat auto or AI autopilot when a run starts; F9 returns to Manual. The autopilot's network and reviewed rules win 73.1% of 2,442 A20 three-key Heart runs in training (71.0% of 1,014 with v0.9 search); it upgrades too rarely and lacks a deckbuilding plan.

**Let an AI agent play:** point Claude Code, Codex or a similar agent at the agent-play branch of the GitHub repository and have it follow AGENT_PLAY.md: "Read https://github.com/Yongzhaooo/sts1-combat-solver/blob/agent-play/AGENT_PLAY.md, follow it to check and configure my Slay the Spire mods (including incompatible mods), then climb the Spire with the solver."

Subscribe with ModTheSpire and BaseMod. Windows x64 and Apple Silicon Macs; Intel Macs are not supported. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG; F6: diagnostics (local only). Finite search does not guarantee optimal play and trails top players on Burning Pact ordering, Snecko Eye and some small loops. Mods that change combat or RNG, such as SaveStateMod and Undo, are not compatible. Glowing Eye (off by default) reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.9.0: Much faster search with a slightly lower win rate and HP saved: effort is set by act and room, and in 259 same-seed whole-run pairs it takes 0.51× the time and wins the Heart 6.2 points less often. The three presets are gone; the new High-speed mode for older PCs warns about weaker play when turned on; new agent mode (above). Macs: Apple Silicon only; Intel Macs should stay on v0.8.0-beta.3. Fixed: a Bronze Orb's last taken card, Warped Tongs and end-of-turn relic order after Toolbox, Forethought+ multi-pick confirm, exhaust of Havoc-played unplayable cards, and the shop's card removal being used up and charged after a reload (a vanilla issue). Thanks to 七月流火（猫火）, who recently reached a streak of about 80 wins (SL-equivalent) playing by hand with the solver and is close to 100.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
