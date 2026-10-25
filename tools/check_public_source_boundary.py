"""Check that no private internal directories (reports/, evidence/) are tracked in git.

Public repositories and release branches must not commit private research reports,
evaluations, or evidence artifacts. Allowed paths such as docs/, licenses, fixtures,
and runtime assets remain untouched.

With --pre-push REMOTE URL it runs as a git pre-push hook (see tools/install_git_hooks.py):
for pushes to the public repository it checks the pushed commits, not the working tree.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
PROHIBITED_ROOTS = ("reports/", "evidence/")
PUBLIC_URL = re.compile(r"github\.com[:/]+yongzhaooo/sts1-combat-solver(\.git)?/?$", re.IGNORECASE)


def is_prohibited(path: str) -> bool:
    norm_lower = path.lstrip("/").lower()
    return norm_lower in ("reports", "evidence") or norm_lower.startswith(PROHIBITED_ROOTS)


def git_paths(args: list[str]) -> list[str]:
    res = subprocess.run(["git", *args], capture_output=True, check=True)
    raw = res.stdout.decode("utf-8", errors="surrogateescape")
    # git log -z still puts newlines between commits; no real path starts with one.
    return [p.lstrip("\n") for p in raw.split("\0") if p.strip("\n")]


def pushed_violations(sha: str, remote: str) -> list[str]:
    """Prohibited paths in the pushed tree, or added by any commit the remote does not have yet."""
    tree = git_paths(["ls-tree", "-r", "-z", "--name-only", sha])
    # A path added and later deleted still publishes its blob, so new commits count too.
    history = git_paths(["log", "-z", "--format=", "--name-only", "--no-renames", "--diff-filter=d",
                         sha, "--not", f"--remotes={remote}"])
    return sorted({p for p in tree + history if is_prohibited(p)})


def report(violations: list[str], what: str) -> None:
    sys.stderr.write(
        f"Public source boundary check FAILED: {len(violations)} private artifact(s) {what}:\n"
    )
    for v in violations[:15]:
        sys.stderr.write(f"  - {v}\n")
    if len(violations) > 15:
        sys.stderr.write(f"  ... and {len(violations) - 15} more\n")
    sys.stderr.write(
        "Prohibited private roots: reports/, evidence/\n"
        "Private research reports and evidence must not be committed to public product trees.\n"
    )


def pre_push(remote: str, url: str, lines) -> int:
    if remote != "origin" and not PUBLIC_URL.search(url.strip()):
        return 0
    failed = False
    for line in lines:
        parts = line.split()
        if len(parts) != 4 or set(parts[1]) == {"0"}:  # malformed or a ref deletion
            continue
        local_ref, sha = parts[0], parts[1]
        try:
            violations = pushed_violations(sha, remote)
        except subprocess.CalledProcessError as err:
            sys.stderr.write(f"Git error checking {local_ref} ({sha}): {err.stderr!r}\n")
            return 2
        if violations:
            report(violations, f"in {local_ref} ({sha[:12]}) pushed to {remote}")
            failed = True
    if failed:
        sys.stderr.write("Push refused. Remove these paths from the pushed history first.\n")
    return 1 if failed else 0


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
    return repo_top, [path for path in tracked if is_prohibited(path)]


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify git-tracked public source boundary.")
    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_ROOT,
        help="Repository root to check (defaults to parent directory of tools/)",
    )
    parser.add_argument("--pre-push", nargs=2, metavar=("REMOTE", "URL"),
                        help="Run as a pre-push hook: read pushed refs from stdin")
    args = parser.parse_args()
    if args.pre_push:
        return pre_push(*args.pre_push, sys.stdin)
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
        report(violations, "tracked under prohibited root(s)")
        return 1

    print(f"OK: Public source boundary verified for '{repo_top}' (no private reports/ or evidence/ tracked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
