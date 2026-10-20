# Agent play: an AI agent climbs the Spire with the solver

You are an AI agent (Claude Code, Codex or similar) with shell and file access on the player's computer. The player
asked you to set up *Slay the Spire 1* with the STS1 Combat Solver and play runs with it. The solver plays every fight;
you make every decision outside combat: Neow, the map path, card rewards, events, campfires, shops, chests and boss
relics. Work through the steps in order, tell the player what you find, and ask before anything you cannot undo.

## 1. Find the game and the mods

- Game folder: Windows usually `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire`; other Steam libraries are
  listed in `<Steam>\steamapps\libraryfolders.vdf`. macOS: `~/Library/Application Support/Steam/steamapps/common/SlayTheSpire`,
  with game files inside `SlayTheSpire.app/Contents/Resources`.
- Workshop mods: `<library>/steamapps/workshop/content/646570/<item id>/`. Local mods: the game's `mods/` folder.
- Required, subscribed on Steam Workshop: ModTheSpire (`1605060445`), BaseMod (`1605833019`) and STS1 Combat Solver
  (`3814773843`, version 0.9.0 or later; `ModTheSpire.json` inside `STS1CombatSolver.jar` shows the version). You cannot
  subscribe for the player: give them the links `https://steamcommunity.com/sharedfiles/filedetails/?id=<id>` and wait.
- Supported: Windows x64 and Apple Silicon Macs. Intel Macs are not supported. Only the Ironclad is supported.

## 2. Remove incompatible mods

Every mod JAR has a `ModTheSpire.json` with its `modid`. Read it from each JAR in the game's `mods/` folder and in each
workshop item folder, then deal with these:

| Mod | Why |
| --- | --- |
| `SaveStateMod`, `undothespire`, `undobutton` | rewrite the action queue and card choices; the solver loses sync |
| `CommunicationMod`, `SteamStateExport` as separate JARs | already built into the solver; duplicates conflict |
| a second `sts1solver` JAR (an old local build) | duplicate mod ID |
| other characters, card, relic, monster or rule mods | the solver simulates the original Ironclad game only |
| anything listed under `mod_warnings` in `decision.json` (step 4) | the solver found it patching the game's RNG |

Cosmetic and UI mods (map colors, tooltips, SuperFastMode at its tested speed) are fine.

How to turn a mod off, after the player agrees:

- In the ModTheSpire launcher the player can untick it. Its enabled set is saved in `mod_lists.json` in the ModTheSpire
  config folder (Windows `%LOCALAPPDATA%\ModTheSpire\`, macOS `~/Library/Preferences/ModTheSpire/`, Linux
  `~/.config/ModTheSpire/`). You may edit the list named by `defaultList` while the game is closed: copy the file to
  `mod_lists.json.bak` first and remove only the incompatible JAR names.
- A local JAR in `mods/`: move it into a new folder `mods-disabled-by-agent/` beside `mods/`. Never delete it.
- A Workshop mod the player no longer wants: ask them to unsubscribe.

## 3. Turn on agent mode

With the game closed, open the solver's settings file: `STS1CombatSolver/config.properties` in the same ModTheSpire config
folder. Create the file if it is missing; keep every existing line. Set:

```properties
agentBridge=true
```

On an older or slow computer also set `highSpeed=true`: every fight then searches 1/20 as much, which is much faster but
loses more fights and HP. Leave it off otherwise.

Ask the player to start the game with **Play with Mods**, enable BaseMod and STS1 Combat Solver in ModTheSpire, and start
an Ironclad run (Ascension 20 for the full challenge). With `agentBridge=true` every new run starts in agent mode:
fights run automatically and each outside decision waits for you. `F9` in the game hands control back to the player.

## 4. The decision loop

The solver writes `agent/decision.json` in its data folder: Windows `%LOCALAPPDATA%\STS1CombatSolver\agent\`, macOS
`~/Library/Application Support/STS1CombatSolver/agent/`, Linux `~/.local/state/STS1CombatSolver/agent/`.

```json
{
  "decision": 12,
  "agent_mode": true,
  "phase": "choose",
  "notice": "...",
  "error": "",
  "mod_warnings": [],
  "game": {"act": 1, "floor": 3, "current_hp": 61, "max_hp": 80, "gold": 140, "deck": [], "relics": [], "potions": [], "map": [], "screen_type": "CARD_REWARD", "screen_state": {}},
  "visible": {"ruby": false, "emerald": false, "sapphire": false, "burning_elites": [], "...": "..."},
  "choices": [{"index": 0, "label": "Shrug It Off", "solver_score": 0.41}, {"index": 1, "label": "skip", "solver_score": 0.12}],
  "solver_pick": 0,
  "solver_rule": "optional: why a hand-written rule chose solver_pick"
}
```

- `phase`: `choose` (your turn), `combat` (the solver is fighting: wait), `waiting` (screens are changing, or a reward
  the solver handles itself: wait), `dead` (the run is over), `no_run` (in the menus).
- When `phase` is `choose`, pick one entry of `choices` and write one line to `agent/command.txt`:
  `<decision> <index>`, for example `12 0`. Write it to a temporary file in the same folder and rename it, so the game
  never reads half a line. The game consumes the file within a frame; `decision.json` then changes.
- `error` explains an ignored command (a stale `decision` number or an index outside the list). Read the file again.
- `agent_mode` false with a `notice`: the player pressed `F9`, or the solver stopped on something it could not do.
  Tell the player the notice. Write `resume` to `command.txt` only when the player wants you to continue.
- Poll every one or two seconds. Fights take from seconds to a few minutes.
- After `dead`, report the run (floor, cause, the decisions you would change). The player starts the next run.

`game` and `visible` hold only what the player could see on screen: no seed, RNG state or future rewards. Do not look for
hidden information elsewhere, and do not edit save files.

## 5. How to decide

`solver_pick` is the solver's own choice: a small network trained on thousands of runs plus reviewed rules. On its own it
wins about 71% of Ascension 20 three-key Heart runs. Treat it as a strong default and override it when you have a concrete
reason about this run. Keep a short plan for the run (what the deck still needs, which keys are left) and update it after
each reward.

- **Path:** prefer routes with campfires and elites. Before the first elite of Act 1, get cards that deal damage now, a
  useful potion and a campfire upgrade. Keep all three keys reachable: the burning elite in Act 3 for the emerald key, a
  campfire for the ruby key, a chest for the sapphire key. Rest when HP is low; elites are a means to grow, not a goal.
- **Shops:** visit when you can afford a removal; with 300 gold or more, shop for removal, key cards and good relics.
  Early, 99 gold can buy a potion to get past an elite.
- **Cards:** take strong immediate damage early; it reduces HP loss and makes elites safe. When defense is short, take
  defensive cards such as Shrug It Off, Flame Barrier or Ghostly Armor. Check cost, draw, scaling and how cards work
  together; once the deck works, skipping is often right. Do not count on a key card showing up later.
- **Campfires:** in Act 1, upgrades are worth a lot; avoid fights that would force you to rest instead.
- The solver uses potions in fights. With Auto potion swap on (panel switch) it also takes and swaps potion rewards;
  otherwise they appear in `choices`. Choose only from `choices`; there is no other command.

The solver is weaker than top players on some special lines (ordering cards for Burning Pact, Snecko Eye, some small
loops). That is inside fights, which you do not control; your part is to give it a deck it can win with.
