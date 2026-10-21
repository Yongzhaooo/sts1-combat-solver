# STS1 Combat Solver — Ironclad

QQ community group: **232459269**. Share your experience and report issues.
If you find it useful, please star the repository on [GitHub](https://github.com/Yongzhaooo/sts1-combat-solver).

You build the deck and pick the path; it fights. Step, turn and automatic execution for the Ironclad and colorless cards.

- Automatic potion use in fights and post-combat potion swaps.
- Plans around relic counters (Happy Flower, Incense Burner, Pen Nib and more) and Hand of Greed and Feed kills.
- Search effort is set by act and room. On older PCs, High-speed mode on the panel searches 1/20 as much: faster, with a lower win rate and more HP lost.

**Full autopilot (early):** choose Manual, Combat auto or AI autopilot when a run starts; F9 returns to Manual. The autopilot's network and reviewed rules win 73.1% of 2,442 A20 three-key Heart runs in training (71.0% of 1,014 with v0.9 search); it upgrades too rarely and lacks a deckbuilding plan.

**Let an AI agent play:** point Claude Code, Codex or a similar agent at the agent-play branch of the GitHub repository and have it follow AGENT_PLAY.md: "Read https://github.com/Yongzhaooo/sts1-combat-solver/blob/agent-play/AGENT_PLAY.md, follow it to check and configure my Slay the Spire mods (including incompatible mods), then climb the Spire with the solver."

Subscribe with ModTheSpire and BaseMod. Windows x64 and Apple Silicon Macs; Intel Macs are not supported. Enter: one step; F10: run this fight; F9: stop; F8: collapse; F7: map PNG; F6: diagnostics (local only). Finite search does not guarantee optimal play and trails top players on Burning Pact ordering, Snecko Oil and some small loops. Mods that change combat or RNG, such as SaveStateMod and Undo, are not compatible. Glowing Eye (off by default) reveals hidden RNG.

[源码与下载 / Source & downloads](https://github.com/Yongzhaooo/sts1-combat-solver)

v0.9.1: Better potion use. After the act 1 and 2 Bosses and the last act 3 Boss, the next act heals 75% of missing HP, so those fights count a quarter of HP lost; when a potion-free win costs at most 50 HP, no potion lines are searched, keeping valuable bottles. Swift, Gambler's Brew, Distilled Chaos, Heart of Iron and Cultist potions are worth more: drunk less casually in fights and preferred as rewards. Fixed: Gambler's Brew was priced as an ordinary potion in fights.

Special thanks to 七月流火（猫火） for long-term testing and feedback.

Silent, Defect and Watcher solvers are open for collaboration: see CONTRIBUTING.md on GitHub or write to yongzhao.derek@gmail.com.
