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

Each step's `ok` means "exited 0", so a step that skipped its work reads like a
step that did it. `skipped_work()` is the short list of checks that report a skip
on stdout, and a step that skipped is marked `skip` and named in the summary
instead of counting as a pass.

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
# The one check that answers `SKIP` on stdout when it has nothing to compare.
SKIPPABLE = "check_card_costs.py"
# The game JAR and the card class tree are machine-local paths, not something the
# repository carries, so they come from the environment. Unset means the check is
# skipped; the workflow step runs the same command with --allow-skip instead.
GAME_JAR = os.environ.get("STS_GAME_JAR", "")
GAME_CLASSES = os.environ.get("STS_GAME_CLASSES", "")


def msbuild_safe_environ() -> dict[str, str]:
    """The environment with each proxy variable kept in one spelling only."""
    names = set(_raw_environment_names())
    upper = {name for name in names if name.isupper()}
    lowered = {name.lower() for name in upper}
    safe = dict(os.environ)
    for name in names:
        if name.lower() in lowered and name not in upper:
            safe.pop(name, None)
    return safe


def _raw_environment_names() -> list[str]:
    """Variable names in this process's Win32 environment block, spelling intact."""
    if sys.platform != "win32":
        return list(os.environ)
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.GetEnvironmentStringsW.restype = ctypes.POINTER(ctypes.c_wchar)
    kernel32.GetEnvironmentStringsW.argtypes = []
    block = kernel32.GetEnvironmentStringsW()
    names, index = [], 0
    while block[index] != "\x00":
        end = index
        while block[end] != "\x00":
            end += 1
        names.append(block[index:end].split("=", 1)[0])
        index = end + 1
    return names


def local_only() -> list[tuple[str, str, object]]:
    """(command, what it needs, is-that-input-here) for checks CI cannot run."""
    return [
        (f"python tools/run_distill2_check.py --jar {GAME_JAR}",
         f"the game JAR (set STS_GAME_JAR), currently {'unset' if not GAME_JAR else GAME_JAR}",
         lambda: Path(GAME_JAR).exists() if GAME_JAR else False),
        (f"python tools/check_card_costs.py --classes {GAME_CLASSES}",
         f"the extracted card classes (set STS_GAME_CLASSES), currently {'unset' if not GAME_CLASSES else GAME_CLASSES}",
         lambda: Path(GAME_CLASSES).is_dir() if GAME_CLASSES else False),
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


# Checks that exit 0 without doing their work. `check_card_costs.py` is the one
# known case: it cannot compare without the game's class tree, CI has none, so the
# workflow passes `--allow-skip` and the command this file runs verbatim exits 0
# and says it skipped. On a machine that does have the tree that looked identical
# to "201 card costs match" -- the step was an `ok` that checked nothing.
def skipped_work(command: str, output: str) -> bool:
    """True when this step ran without comparing anything.

    Keyed on the check rather than on the output alone because every other step
    here prints on a pass, and reading any `SKIP` line as a skipped step would
    mislabel them. Only `--classes`/`STS_GAME_CLASSES` reaches the comparison, so
    a run that names the check and never says `PASS` compared nothing -- including
    the packages it may have read before stopping at its first `FAIL`.
    """
    if SKIPPABLE not in command:
        return False
    return not any(line.startswith("PASS:") for line in output.splitlines())


def machine_path() -> list[str]:
    """The machine's own PATH, in the order Windows resolves it.

    Appending `os.environ["PATH"]` instead is not enough: a launcher can hand a
    child process a PATH that is only its own tools directory, and this machine's
    agent harness does exactly that. The four checks that shell out to `git` and
    `node` then die with `[WinError 2]` and a subprocess traceback that never
    names the missing executable, which reads as "the check is broken" rather
    than "the environment is". Reading both the user and the machine PATH is what
    CI gets from `$GITHUB_PATH` plus the runner image, and it is what a plain
    terminal on this machine gets too.
    """
    if os.name != "nt":
        return [os.environ.get("PATH", "")]
    import winreg

    parts = [os.environ.get("PATH", "")]
    keys = (
        (winreg.HKEY_CURRENT_USER, "Environment"),
        (winreg.HKEY_LOCAL_MACHINE, "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"),
    )
    for root, key in keys:
        try:
            with winreg.OpenKey(root, key) as handle:
                value, _ = winreg.QueryValueEx(handle, "Path")
        except OSError:
            continue
        parts.append(os.path.expandvars(value))
    return parts


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
    #
    # It does not prevent MSB6001 on this machine: that one is caused by the
    # process environment carrying the same proxy variable in two spellings
    # (`HTTPS_PROXY` from the user registry, `https_proxy` from the MSYS shell).
    # MSBuild copies the environment into a StringDictionaryWithComparer, which
    # ignores key case, and throws "已添加项。字典中的关键字" on the second one --
    # from ToolTask.GetProcessStartInfo, before cl.exe ever starts. When it hits
    # cmake's compiler probe instead of a real build, the symptom is the
    # misleading "No CMAKE_CXX_COMPILER could be found". Keeping one spelling
    # per variable is the fix; see pitfall 3 in docs/local-environment.md.
    # os.environ cannot deduplicate these: it is case-insensitive on Windows and
    # collapses HTTPS_PROXY/https_proxy into one entry, so the duplicate has to be
    # read from the raw environment block. See pitfall 3 in docs/local-environment.md.
    env = {
        **msbuild_safe_environ(),
        "PYTHONUTF8": "1",
        "PYTHONIOENCODING": "utf-8",
        "MSBUILDDISABLENODEREUSE": "1",
        "PATH": os.pathsep.join([str(Path(sys.executable).parent), *machine_path()]),
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
    skipped_steps: list[tuple[str, str]] = []
    started = time.monotonic()
    for index, command in enumerate(checks, start=1):
        ok, elapsed, output = run(command)
        # A skip is not a pass and not a failure: the step ran, exited 0, and said
        # it compared nothing. Counting it as `ok` is what made this file unable to
        # tell "all green" from "one check was asleep".
        skipped = ok and skipped_work(command, output)
        mark = "skip" if skipped else ("ok  " if ok else "FAIL")
        say(f"[{index:>2}/{len(checks)}] {mark} {elapsed:6.1f}s  {command}")
        if skipped:
            skipped_steps.append((command, output))
            if args.verbose:
                say(f"        {output.replace(chr(10), chr(10) + '        ')}")
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
    if skipped_steps:
        say(f"{len(skipped_steps)} of {len(checks)} checks skipped their own work "
            f"(exit 0, nothing compared) in {elapsed:.0f}s:")
        for command, output in skipped_steps:
            say(f"  {command}")
            for line in output.splitlines():
                if line.startswith("SKIP:"):
                    say(f"       {line}")
        say()
        say("A skipped check is not a passing one: give it its input, or say why.")
        say()

    if not failures:
        passed = len(checks) - len(skipped_steps)
        say(f"{passed} of {len(checks)} checks passed in {elapsed:.0f}s"
            + (f", {len(skipped_steps)} skipped." if skipped_steps else ". CI should agree."))
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
    for command, need, available in local_only():
        if not available():
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
