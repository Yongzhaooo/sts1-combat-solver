"""Find the recordings a card play can be graded from, without being told the card's name.

Ticket #8, part (ii). The check this replaces named four windows and the card it expected in each,
so it could only ever grade Neutralize. A recording already carries everything the grade needs: the play it took (which card, from which hand slot, at which monster), the board it opened
on and the board it left behind. So the grade reads the recording instead of a table.
"""
from __future__ import annotations

import gzip
import json
from pathlib import Path
from typing import NamedTuple


class Window(NamedTuple):
    """One recording a card play can be graded from, read out of the recording itself."""

    path: Path
    case: dict
    card: str
    slot: int
    purpose: str

    @property
    def id(self) -> str:
        return self.case["id"]


def read_fixture(path) -> dict:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        return json.load(handle)


def graded(case) -> bool:
    """True when this recording can be graded as a card play.

    The grade reads the opening board, replays the recording's own play onto it, and holds the
    result against the board the recording took next. So a graded recording opens with a play and
    has that second board. A recording that ends first, does something else first, or stops after
    one board offers nothing to replay or nothing to compare against.
    """
    if len(case.get("checkpoints") or []) < 2:
        return False
    actions = case.get("actions") or []
    return bool(actions) and actions[0].get("kind") == "play"


def purpose(case) -> str:
    """What this recording exists to show, as the recording states it.

    Refused rather than defaulted: a recording nobody wrote a purpose for is exactly what the old
    hand-written table was there to catch.
    """
    stated = (case.get("purpose") or "").strip()
    if not stated:
        raise ValueError(f"{case.get('id', 'a recording')} states no purpose")
    return stated


def discover(targets=None, default=None):
    """Every declared graded window under the targets, and the ones that declared nothing.

    A target is a directory (searched for recordings) or a single recording file. With no targets
    the default directory is used, so the caller does not have to say where the recordings live.

    Returns `(windows, undeclared)`, both sorted by fixture id. `declared` is the entry ticket: a
    window that states its purpose is graded, one that does not is reported back rather than
    dropped, because a window nobody accounted for is what a grade silently turns into a no-op
    without. A window that is not a card play at all -- it ends first, or has one board -- is
    neither: there is nothing to grade and nothing missing.
    """
    roots = [Path(target) for target in (targets or [])]
    if not roots:
        roots = [Path(default)] if default else []
    found = []
    for root in roots:
        if root.is_dir():
            found.extend(sorted(root.rglob("*.json.gz")))
        elif root.exists():
            found.append(root)
    windows = []
    undeclared = []
    for path in found:
        case = read_fixture(path)
        if not graded(case):
            continue
        if not (case.get("purpose") or "").strip():
            undeclared.append(case.get("id", path.name))
            continue
        action = case["actions"][0]
        windows.append(Window(path=path, case=case, card=action["card"],
                              slot=action["hand"], purpose=purpose(case)))
    return (sorted(windows, key=lambda window: window.id), sorted(undeclared))
