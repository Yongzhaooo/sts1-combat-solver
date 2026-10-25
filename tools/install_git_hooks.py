"""Install the pre-push public source boundary hook into this clone's hooks directory.

The hook and a copy of the checker go into `git rev-parse --git-path hooks` (shared by
all worktrees, or core.hooksPath when set), so pushing an old branch whose tree predates
the checker is still checked. Re-run after tools/check_public_source_boundary.py changes.
"""
from pathlib import Path
import shutil
import subprocess
import sys

TOOLS_DIR = Path(__file__).resolve().parent
MARKER = "# installed by tools/install_git_hooks.py"
HOOK = f"""#!/bin/sh
{MARKER}
hook_dir=$(dirname "$0")
py=python
command -v python >/dev/null 2>&1 || py=python3
export PYTHONUTF8=1
exec "$py" "$hook_dir/check_public_source_boundary.py" --pre-push "$1" "$2"
"""


def main() -> int:
    res = subprocess.run(["git", "-C", str(TOOLS_DIR), "rev-parse", "--path-format=absolute", "--git-path", "hooks"],
                         capture_output=True, text=True, check=True)
    hooks = Path(res.stdout.strip())
    hook = hooks / "pre-push"
    if hook.exists() and MARKER not in hook.read_text(encoding="utf-8", errors="replace"):
        sys.stderr.write(f"Refusing to overwrite existing hook {hook}; merge it by hand.\n")
        return 1
    hooks.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(TOOLS_DIR / "check_public_source_boundary.py", hooks / "check_public_source_boundary.py")
    hook.write_bytes(HOOK.encode("utf-8"))
    hook.chmod(0o755)
    print(f"Installed pre-push boundary hook in {hooks}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
