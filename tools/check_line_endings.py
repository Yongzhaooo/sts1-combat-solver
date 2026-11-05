"""Check that every tracked text file stores LF line endings in the index.

`CONTRIBUTING.md` asks for LF, but nothing enforced it. That matters more on this
machine than elsewhere: `core.autocrlf=false` (see `docs/local-environment.md`),
so the index keeps exactly what was written, and a tool that writes `\r\n` puts
CRLF into the blob. The whole file then shows as rewritten in `git diff`, which
hides the actual change -- that happened twice while recording Silent fixtures.

The index is the right place to look, not the work tree: this machine's work tree
is CRLF for 186 tracked files by default, so a work-tree check would fail on them
for no reason. `git ls-files --eol` reports what each entry holds (`i/lf`, `i/crlf`,
`i/mixed`, `i/none` for empty and `i/-text` for binary) without reading the files.

  python tools/check_line_endings.py            # the whole index
  python tools/check_line_endings.py --root .   # an explicit checkout
"""
import argparse
import subprocess
import sys
from pathlib import Path


def entries(root: Path) -> list[tuple[str, str]]:
    """Every tracked path with the line endings stored for it in the index."""
    out = subprocess.run(["git", "-C", str(root), "ls-files", "--eol", "-z"],
                         capture_output=True, check=True).stdout
    found = []
    for record in out.split(b"\0"):
        if not record:
            continue
        head, _, path = record.partition(b"\t")
        fields = head.split()
        found.append((fields[0].decode(), path.decode("utf-8", "surrogateescape")))
    return found


def problems(root: Path) -> tuple[list[str], int]:
    """Paths whose stored bytes contain CRLF, and how many text files were checked."""
    found = entries(root)
    bad = [f"{path}: the index stores {stored} line endings, not LF"
           for stored, path in found if stored in {"i/crlf", "i/mixed"}]
    text = sum(1 for stored, _ in found if stored != "i/-text")
    return bad, text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    bad, text = problems(root)
    if bad:
        print("\n".join(bad))
        sys.exit(f"{len(bad)} tracked files store CRLF in the index; CONTRIBUTING.md asks for LF")
    print(f"PASS: all {text} tracked text files store LF line endings in the index")
    return 0


if __name__ == "__main__":
    sys.exit(main())
