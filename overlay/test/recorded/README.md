# Recorded game states

These are `game_state` exports captured verbatim from real games by the contributor
working on the Defect combat simulator. They are **not** part of the maintainer's
fixture set: everything directly under `overlay/test/`, plus `fixtures/calibration/`
and `fixtures/combat-exams/`, was written by the maintainer and is left untouched.

Each file is one CommunicationMod state, the same shape as `overlay/test/live-root.json`.
`tools/check_snapshot_import.py` feeds every one of them through the live import path —
`view_from_state` → `build_snapshot` → `from_snapshot` → `compare_battle` — and asserts the
engine reproduces the state field by field. Each file is kept because importing it caught a
real defect; the state is the evidence, not a snapshot of current output.

| File | Recorded state | What it pins down |
| --- | --- | --- |
| `DEFECT/machine-learning-root.json` | A3, floor 4, Machine Learning active | the game reports that power as `Draw`; importing it raised `unknown enum name: Draw` |
| `DEFECT/creative-ai-root.json` | A3, floor 7, Creative AI active, 2-cost Buffer in hand | the engine made every card Creative AI and Hello World add free; the game keeps the printed cost |
| `DEFECT/steam-barrier-root.json` | A3, floor 3, one Steam Barrier already played | the game lowers the card's own `baseBlock` and leaves `misc` at 0; the engine counts plays in `specialData`, so the importer derives it from the block |

Notes:

- No personal data. The payload is a combat state — deck, relics, hand, monsters — and the
  seed identifies the run; nothing else.
- The class gate in `backend.SUPPORTED_CLASSES` guards the live product, so the test widens it
  for the duration of one fixture load and restores it afterwards.
- A Defect snapshot carries a signed Java seed; the test normalizes it to uint64 exactly as
  `backend.py` does before handing it to the native importer.
- Add a file here only with a state that pinned a real bug, and add its row to the table.
  Class subdirectories follow the naming already used by `fixtures/calibration/`.
