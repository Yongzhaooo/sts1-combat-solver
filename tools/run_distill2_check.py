"""Run overlay/test/Distill2Check.java against a real game JAR, locally.

`overlay/build.py` compiles and runs `Distill2Check` as part of the mod build, but
that build needs the game JAR and is not part of CI (`check_distill2_artifact.py`
only hashes the shipped weights and schema). So the one check that proves every
original-game card/relic/potion/event ID resolves to a simulator ID had no
copy-pasteable local command: it was assembled by hand, and the hand-assembled
version failed on a MSYS path (`/tmp/...`) that Java does not understand.

This script does that assembly: compile `Distill2.java` and `Distill2Check.java`
into a temp directory, copy `overlay/resources` next to the classes (Distill2
loads the schema as a resource), and run the check.

  python tools/run_distill2_check.py --jar E:/sts-devkit/jar-scratch/sts-truth/desktop-1.0.jar

Needs a JDK on PATH (`javac` and `java`); the JAR is the caller's, since game
assets are never committed. The temp directory is made with `tempfile.mkdtemp`,
which returns a Windows path, so `java -cp` can use it.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ("overlay/src/sts1solver/Distill2.java", "overlay/test/Distill2Check.java")
RESOURCES = "overlay/resources"
REFERENCE = "overlay/test/distill2-reference.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, required=True, help="the game JAR that holds the original-game ID constants")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    jar = args.jar.resolve()
    if not jar.exists():
        sys.exit(f"FAIL: no game JAR at {jar}")

    for tool in ("javac", "java"):
        if shutil.which(tool) is None:
            sys.exit(f"FAIL: {tool} is not on PATH; a JDK is required")

    with tempfile.TemporaryDirectory(prefix="distill2-check-") as tmp:
        classes = Path(tmp) / "classes"
        classes.mkdir()
        sources = [str(root / source) for source in SOURCES]
        compile_command = ["javac", "--release", "8", "-proc:none", "-encoding", "UTF-8",
                           "-cp", str(jar), "-d", str(classes), *sources]
        compiled = subprocess.run(compile_command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if compiled.returncode != 0:
            sys.exit("FAIL: javac refused the sources:\n" + (compiled.stdout + compiled.stderr).strip())

        for resource in (root / RESOURCES).iterdir():
            target = classes / resource.name
            if resource.is_dir():
                # javac already wrote package directories into classes/; merge instead of replacing.
                shutil.copytree(resource, target, dirs_exist_ok=True)
            else:
                shutil.copyfile(resource, target)

        run_command = ["java", "-Dfile.encoding=UTF-8", "-cp", f"{classes}{';' if sys.platform == 'win32' else ':'}{jar}",
                       "sts1solver.Distill2Check", str(root / REFERENCE)]
        ran = subprocess.run(run_command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        output = (ran.stdout + ran.stderr).strip()
        if ran.returncode != 0:
            sys.exit("FAIL: Distill2Check reported:\n" + output)
        print(output)
        return 0


if __name__ == "__main__":
    sys.exit(main())
