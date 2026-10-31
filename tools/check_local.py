"""Run the same checks CI runs, locally, in the same order.

`CONTRIBUTING.md` lists five commands to run before committing, but
`.github/workflows/native-runtime.yml` runs every check in the `smoke` job, so
"it passed locally" and "CI will pass" were different claims. This closes that
gap by reading the workflow and executing what it executes, instead of keeping a
second list that goes stale the next time the maintainer adds a check. Nothing
here records how many checks there are: `--list` is the answer, and it follows
the workflow.

The workflow is parsed with a regex rather than a YAML library because the
repo prefers the standard library and `pyyaml` is not installed.

  python tools/check_local.py            # run everything, stop nowhere
  python tools/check_local.py --list     # show what would run
  python tools/check_local.py --stop     # stop at the first failure

Every command runs under `sys.executable`, so invoke this with the interpreter
your build uses, not whatever `python` happens to be first on PATH. `python -m
pip install ...` steps are listed as setup and skipped, since the environment
already has the pinned pybind11/cmake.

The macos-package job is skipped: it is self-hosted on the maintainer's Mac and
external pull requests never reach it.

After the workflow's own steps, a short list of local-only checks runs when its
inputs exist (a game JAR for `Distill2Check`). They are reported separately and
never affect the CI count, because CI cannot run them: game assets are not
committed. Skipped ones say what they need.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "native-runtime.yml"
JOB = "smoke"
SETUP_PREFIXES = ("python -m pip", "pip install")
# The game JAR is a machine-local path, not something the repository carries,
# so it comes from the environment. Unset means the check is skipped.
GAME_JAR = os.environ.get("STS_GAME_JAR", "")


def local_only() -> list[tuple[str, str]]:
    """Checks that prove something CI cannot, each with what it needs."""
    return [
        (f"python tools/run_distill2_check.py --jar {GAME_JAR}",
         f"the game JAR (set STS_GAME_JAR), currently {'unset' if not GAME_JAR else GAME_JAR}"),
    ]


RUN_LINE = re.compile(r"^(?P<indent>\s*)(?:-\s*)?run:\s*(?P<value>.*)$")
STEP_LINE = re.compile(r"^\s*-\s*(?:name|uses|if|env|with):")


def smoke_commands(workflow: Path = WORKFLOW) -> list[str]:
    """Every `python ...` command in the `smoke` job, in file order.

    Reads the job block between `  smoke:` and the next two-space-indented
    sibling job, then picks up single-line `- run: python ...` commands and the
    `python ...` lines inside multi-line `run: |` blocks. Anything that is not
    a python invocation (checkout, setup-java, shell glue) is ignored.
    """
    lines = workflow.read_text(encoding="utf-8").splitlines()
    start = None
    end = len(lines)
    for index, line in enumerate(lines):
        if line.strip() == f"{JOB}:" and not line.startswith("   "):
            start = index + 1
            continue
        if start is not None and line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(":"):
            end = index
            break
    if start is None:
        raise SystemExit(f"{workflow} has no `{JOB}` job")

    commands: list[str] = []
    block_indent: int | None = None
    for line in lines[start:end]:
        if not line.strip():
            continue
        match = RUN_LINE.match(line)
        if match:
            value = match.group("value").strip()
            block_indent = len(match.group("indent")) if value in {"|", ">", "|-", ">-"} else None
            if value.startswith("python "):
                commands.append(value)
            continue
        if STEP_LINE.match(line):
            block_indent = None
            continue
        if block_indent is not None and len(line) - len(line.lstrip()) > block_indent:
            value = line.strip()
            if value.startswith("python "):
                commands.append(value)
    return commands


def is_setup(command: str) -> bool:
    return command.startswith(SETUP_PREFIXES)


def run(command: str) -> tuple[bool, float, str]:
    started = time.monotonic()
    args = command.split()
    args[0] = sys.executable
    # CI sets both of these; without them a Chinese Windows console raises
    # UnicodeEncodeError on the first non-GBK character a check prints.
    # CI also puts the interpreter's bin directory on PATH (`$GITHUB_PATH`),
    # which is how `cmake` and `ninja` from the venv become reachable; the
    # build fails with FileNotFoundError on `cmake` without it.
    # MSBUILDDISABLENODEREUSE keeps MSBuild from reusing a node across steps.
    # Without it the build dies in CL.exe's wrapper: this machine has both
    # `NO_PROXY` (registry) and `no_proxy` (injected by the shell), and MSBuild
    # folds them into one Hashtable, which throws on the case-duplicate key:
    #   MSB6001 ... System.ArgumentException: 已添加项。字典中的关键字:"NO_PROXY"
    env = {
        **os.environ,
        "PYTHONUTF8": "1",
        "PYTHONIOENCODING": "utf-8",
        "MSBUILDDISABLENODEREUSE": "1",
        "PATH": os.pathsep.join([str(Path(sys.executable).parent), os.environ.get("PATH", "")]),
    }
    result = subprocess.run(
        args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env
    )
    elapsed = time.monotonic() - started
    output = (result.stdout + result.stderr).strip()
    return result.returncode == 0, elapsed, output


def say(text: str = "") -> None:
    """Print without dying on a console whose encoding lacks the character."""
    encoding = sys.stdout.encoding or "utf-8"
    print(text.encode(encoding, errors="replace").decode(encoding), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="show the commands and exit")
    parser.add_argument("--stop", action="store_true", help="stop at the first failure instead of running everything")
    parser.add_argument("--verbose", action="store_true", help="print each command's output even when it passes")
    args = parser.parse_args()

    commands = smoke_commands()
    checks = [command for command in commands if not is_setup(command)]
    if args.list:
        say(f"{len(checks)} checks in the `{JOB}` job of {WORKFLOW.relative_to(ROOT)}:")
        for command in checks:
            say(f"  {command}")
        return 0

    say(f"{len(checks)} checks, one per CI step. Interpreter: {sys.executable}")
    failures: list[tuple[str, str]] = []
    started = time.monotonic()
    for index, command in enumerate(checks, start=1):
        ok, elapsed, output = run(command)
        mark = "ok  " if ok else "FAIL"
        say(f"[{index:>2}/{len(checks)}] {mark} {elapsed:6.1f}s  {command}")
        if not ok:
            failures.append((command, output))
            tail = "\n".join(output.splitlines()[-12:])
            if tail:
                say(f"        {tail.replace(chr(10), chr(10) + '        ')}")
            if args.stop:
                break
        elif args.verbose and output:
            say(output)

    elapsed = time.monotonic() - started
    say()
    if not failures:
        say(f"All {len(checks)} checks passed in {elapsed:.0f}s. CI should agree.")
    else:
        say(f"{len(failures)} of {len(checks)} checks failed in {elapsed:.0f}s:")
        for command, _ in failures:
            say(f"  {command}")
        say()
        say("A failure here is cheaper than a red PR: fix it before pushing.")
        return 1

    say()
    say("Beyond CI (needs inputs the repository does not carry):")
    skipped = passed = 0
    for command, need in local_only():
        jar = Path(GAME_JAR) if GAME_JAR else None
        if jar is None or not jar.exists():
            say(f"  skip {command}")
            say(f"       needs {need}")
            skipped += 1
            continue
        ok, took, output = run(command)
        say(f"  {'ok  ' if ok else 'FAIL'} {took:6.1f}s  {command}")
        if not ok:
            say(f"       {' '.join(output.splitlines()[-6:])}")
            failures.append((command, output))
        else:
            passed += 1
    if failures:
        say()
        say("A local-only failure is still a real failure: the ID table is what the pilot reads.")
        return 1
    if passed:
        say(f"  {passed} passed, {skipped} skipped")
    else:
        say("  all skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
