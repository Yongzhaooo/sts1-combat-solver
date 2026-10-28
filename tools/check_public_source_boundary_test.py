"""Regression tests for check_public_source_boundary.py.

Verifies:
1. Public allowed files pass (docs, overlay, LICENSE, etc.)
2. ASCII private reports/evidence fail
3. Non-ASCII/Chinese private paths (e.g. reports/中文报告.md) fail (C-quoting regression)
4. Private paths with spaces (e.g. reports/spaced report.md) fail
5. Ignored and untracked private files do not falsely fail
6. Passing a subfolder to --root rejects non-root with an error
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS_DIR = Path(__file__).resolve().parent
SCRIPT = TOOLS_DIR / "check_public_source_boundary.py"


def run_guard(root: Path) -> tuple[int, str, str]:
    cmd = [sys.executable, str(SCRIPT), "--root", str(root)]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    return proc.returncode, proc.stdout, proc.stderr


class CheckPublicSourceBoundaryTest(unittest.TestCase):
    def test_public_allowed_files_pass(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-public-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            (repo / "docs").mkdir()
            (repo / "overlay").mkdir()
            (repo / "LICENSE").write_text("MIT", encoding="utf-8")
            (repo / "README.md").write_text("# Public", encoding="utf-8")
            (repo / "docs" / "index.md").write_text("# Docs", encoding="utf-8")
            (repo / "overlay" / "native.cpp").write_text("// C++", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=str(repo), check=True)

            code, out, err = run_guard(repo)
            self.assertEqual(code, 0, f"Expected 0, got {code}. stderr: {err}")
            self.assertIn("Public source boundary verified", out)

    def test_ascii_private_report_fails(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-ascii-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            (repo / "reports" / "experiments").mkdir(parents=True)
            (repo / "reports" / "experiments" / "result.md").write_text("private", encoding="utf-8")
            subprocess.run(["git", "add", "reports/experiments/result.md"], cwd=str(repo), check=True)

            code, out, err = run_guard(repo)
            self.assertEqual(code, 1, f"Expected 1, got {code}. stdout: {out}")
            self.assertIn("Public source boundary check FAILED", err)
            self.assertIn("reports/experiments/result.md", err)

    def test_chinese_private_name_fails(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-chinese-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            (repo / "reports").mkdir()
            (repo / "reports" / "中文报告.md").write_text("测试", encoding="utf-8")
            subprocess.run(["git", "add", "reports/中文报告.md"], cwd=str(repo), check=True)

            code, out, err = run_guard(repo)
            self.assertEqual(code, 1, f"Expected 1, got {code}. stdout: {out}")
            self.assertIn("Public source boundary check FAILED", err)
            self.assertIn("reports/中文报告.md", err)

    def test_spaced_private_name_fails(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-space-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            (repo / "evidence" / "run trace").mkdir(parents=True)
            (repo / "evidence" / "run trace" / "output data.json").write_text("{}", encoding="utf-8")
            subprocess.run(["git", "add", "evidence/run trace/output data.json"], cwd=str(repo), check=True)

            code, out, err = run_guard(repo)
            self.assertEqual(code, 1, f"Expected 1, got {code}. stdout: {out}")
            self.assertIn("Public source boundary check FAILED", err)
            self.assertIn("evidence/run trace/output data.json", err)

    def test_ignored_and_untracked_outputs_pass(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-ignored-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            (repo / ".gitignore").write_text("/reports/\n/evidence/\n", encoding="utf-8")
            subprocess.run(["git", "add", ".gitignore"], cwd=str(repo), check=True)

            (repo / "reports").mkdir()
            (repo / "reports" / "untracked.csv").write_text("1,2,3", encoding="utf-8")
            (repo / "evidence").mkdir()
            (repo / "evidence" / "untracked.json").write_text("{}", encoding="utf-8")

            code, out, err = run_guard(repo)
            self.assertEqual(code, 0, f"Expected 0, got {code}. stderr: {err}")
            self.assertIn("Public source boundary verified", out)

    def test_subfolder_rejected_as_non_root(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-subfolder-") as td:
            repo = Path(td)
            subprocess.run(["git", "init", "-b", "main"], cwd=str(repo), check=True, capture_output=True)
            sub = repo / "subfolder"
            sub.mkdir()
            (sub / "file.txt").write_text("test", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=str(repo), check=True)

            code, out, err = run_guard(sub)
            self.assertEqual(code, 2, f"Expected 2, got {code}. stdout: {out}")
            self.assertIn("not the repository root", err)

    def test_pre_push_checks_pushed_commits_not_worktree(self):
        with tempfile.TemporaryDirectory(prefix="boundary-test-prepush-") as td:
            repo = Path(td)

            def git(*args):
                return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=str(repo),
                                      check=True, capture_output=True, text=True).stdout.strip()

            def push(remote, url, sha):
                line = f"refs/heads/b {sha} refs/heads/b {'0' * 40}\n"
                proc = subprocess.run([sys.executable, str(SCRIPT), "--pre-push", remote, url], cwd=str(repo),
                                      input=line, capture_output=True, text=True, encoding="utf-8")
                return proc.returncode, proc.stderr

            git("init", "-b", "main")
            (repo / "README.md").write_text("# Public", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "clean")
            clean = git("rev-parse", "HEAD")
            (repo / "reports").mkdir()
            (repo / "reports" / "x.md").write_text("private", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "leak")
            leak = git("rev-parse", "HEAD")
            git("rm", "-q", "reports/x.md")
            git("commit", "-m", "hide")
            hidden = git("rev-parse", "HEAD")
            git("checkout", "-q", clean)  # the checked-out tree is clean; the pushed commit is not

            url = "https://github.com/Yongzhaooo/sts1-combat-solver.git"
            code, err = push("public", url, leak)
            self.assertEqual(code, 1, err)
            self.assertIn("reports/x.md", err)
            code, err = push("origin", "/tmp/fake.git", hidden)
            self.assertEqual(code, 1, "a path added then deleted is still in the pushed history")
            self.assertEqual(push("origin", url, clean)[0], 0)
            self.assertEqual(push("private", "git@example.com:me/private.git", leak)[0], 0)
            deletion = subprocess.run([sys.executable, str(SCRIPT), "--pre-push", "origin", url], cwd=str(repo),
                                      input=f"(delete) {'0' * 40} refs/heads/b {leak}\n", capture_output=True, text=True)
            self.assertEqual(deletion.returncode, 0)

if __name__ == "__main__":
    unittest.main()
