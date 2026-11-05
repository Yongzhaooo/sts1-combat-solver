"""Install the repository's git hooks into this clone's hooks directory.

Two hooks, both with a copy of the checker they call:

* `pre-push` runs the public source boundary check, so pushing an old branch whose
  tree predates the checker is still checked.
* `pre-commit` runs the line-ending check. `CONTRIBUTING.md` asks for LF, and on a
  clone with `core.autocrlf=false` nothing else catches a tool that writes CRLF --
  the whole file then shows as rewritten and the real change is lost in the noise.

The hooks go into `git rev-parse --git-path hooks` (shared by all worktrees, or
core.hooksPath when set). Re-run after either checker changes.
"""
from pathlib import Path
import shutil
import subprocess
import sys

TOOLS_DIR = Path(__file__).resolve().parent
MARKER = "# installed by tools/install_git_hooks.py"

PRE_PUSH = f"""#!/bin/sh
{MARKER}
hook_dir=$(dirname "$0")
py=python
command -v python >/dev/null 2>&1 || py=python3
export PYTHONUTF8=1
exec "$py" "$hook_dir/check_public_source_boundary.py" --pre-push "$1" "$2"
"""

PRE_COMMIT = f"""#!/bin/sh
{MARKER}
hook_dir=$(dirname "$0")
py=python
command -v python >/dev/null 2>&1 || py=python3
export PYTHONUTF8=1
exec "$py" "$hook_dir/check_line_endings.py" --root "$(git rev-parse --show-toplevel)"
"""

HOOKS = {
    "pre-push": ("check_public_source_boundary.py", PRE_PUSH),
    "pre-commit": ("check_line_endings.py", PRE_COMMIT),
}


def main() -> int:
    res = subprocess.run(["git", "-C", str(TOOLS_DIR), "rev-parse", "--path-format=absolute", "--git-path", "hooks"],
                         capture_output=True, text=True, check=True)
    hooks = Path(res.stdout.strip())
    hooks.mkdir(parents=True, exist_ok=True)
    for name, (checker, text) in HOOKS.items():
        hook = hooks / name
        if hook.exists() and MARKER not in hook.read_text(encoding="utf-8", errors="replace"):
            sys.stderr.write(f"Refusing to overwrite existing hook {hook}; merge it by hand.\n")
            return 1
        shutil.copyfile(TOOLS_DIR / checker, hooks / checker)
        hook.write_bytes(text.encode("utf-8"))
        hook.chmod(0o755)
        print(f"Installed {name} hook in {hooks}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
