"""Check that no private internal directories (reports/, evidence/) are tracked in git.

Public repositories and release branches must not commit private research reports,
evaluations, or evidence artifacts. Allowed paths such as docs/, licenses, fixtures,
and runtime assets remain untouched.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
PROHIBITED_ROOTS = ("reports/", "evidence/")


def get_repo_top(root: Path) -> Path:
    res = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return Path(res.stdout.strip()).resolve()


def get_tracked_files(repo_top: Path) -> list[str]:
    cmd = ["git", "-C", str(repo_top), "ls-files", "-z"]
    res = subprocess.run(cmd, capture_output=True, check=True)
    raw = res.stdout.decode("utf-8", errors="surrogateescape")
    # NUL-delimited output preserves Chinese characters and spaces without C-quoting.
    # Preserve raw filenames without stripping whitespace.
    return [f.replace("\\", "/") for f in raw.split("\0") if f]


def check_boundary(root: Path) -> tuple[Path, list[str]]:
    repo_top = get_repo_top(root)
    if not os.path.samefile(root, repo_top):
        raise ValueError(
            f"Specified root '{root}' is not the repository root (expected '{repo_top}'). "
            "Boundary check must run against the top-level repository."
        )

    tracked = get_tracked_files(repo_top)
    violations = []
    for path in tracked:
        norm = path.lstrip("/")
        norm_lower = norm.lower()
        if norm_lower in ("reports", "evidence"):
            violations.append(path)
            continue
        for prefix in PROHIBITED_ROOTS:
            if norm_lower.startswith(prefix):
                violations.append(path)
                break
    return repo_top, violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify git-tracked public source boundary.")
    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_ROOT,
        help="Repository root to check (defaults to parent directory of tools/)",
    )
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        repo_top, violations = check_boundary(root)
    except subprocess.CalledProcessError as err:
        err_msg = err.stderr.strip() if err.stderr else str(err)
        sys.stderr.write(f"Git error checking '{root}': {err_msg}\n")
        return 2
    except (ValueError, FileNotFoundError) as err:
        sys.stderr.write(f"Error checking repository root '{root}': {err}\n")
        return 2

    if violations:
        sys.stderr.write(
            f"Public source boundary check FAILED: {len(violations)} private artifact(s) tracked under prohibited root(s):\n"
        )
        for v in violations[:15]:
            sys.stderr.write(f"  - {v}\n")
        if len(violations) > 15:
            sys.stderr.write(f"  ... and {len(violations) - 15} more\n")
        sys.stderr.write(
            "Prohibited private roots: reports/, evidence/\n"
            "Private research reports and evidence must not be committed to public product trees.\n"
        )
        return 1

    print(f"OK: Public source boundary verified for '{repo_top}' (no private reports/ or evidence/ tracked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
