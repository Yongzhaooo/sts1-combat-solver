"""Generate the test-only backend used while another class is still in progress.

The production backend is the single source of truth: this copies it and opens
the class gate, so the two copies cannot drift the way a hand-edited duplicate
would (that drift is what made the live Defect import fail).
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "overlay" / "backend.py"
TARGET = ROOT / "overlay" / "backend_defect_test.py"
GATE = "SUPPORTED_CLASSES = {'IRONCLAD': '战士'}"
OPENED = "SUPPORTED_CLASSES = {'IRONCLAD': '战士', 'DEFECT': '机器人'}  # TEST BUILD ONLY"


def main():
    text = SOURCE.read_text(encoding="utf-8")
    if GATE not in text:
        sys.exit("backend.py no longer carries the expected class gate; update this script")
    TARGET.write_text(text.replace(GATE, OPENED), encoding="utf-8")
    print("wrote", TARGET)


if __name__ == "__main__":
    main()