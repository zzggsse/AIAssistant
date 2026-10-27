"""Repository guardrail and evaluation harness for AI-assisted code changes.

The harness keeps the agent on the repository rules and runs the existing
Android verification commands. It is intentionally standard-library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence


APP_GRADLE = Path("app/build.gradle")
CORE_FILES = {
    "app/src/main/java/com/aibutler/app/AgentEngine.kt",
    "app/src/main/java/com/aibutler/app/DeviceTools.kt",
    "app/src/main/java/com/aibutler/app/AssistantAccessibilityService.kt",
}
CODE_SUFFIXES = {".kt", ".java", ".xml", ".gradle", ".py", ".ps1", ".bat"}
EXCLUDED_DIRS = {".git", ".gradle", ".gradle-codex", ".idea", "build", "androidx", "_tmp"}
GUARDRAIL_SCAN_EXEMPT = {"tools/code_harness.py", "tools/test_code_harness.py"}
FORBIDDEN_PATH_PATTERNS = (
    re.compile(r"(^|/)_tmp/"),
    re.compile(r"(^|/)测试结果/"),
    re.compile(r"\.jks$"),
    re.compile(r"\.keystore$"),
    re.compile(r"(^|/)\.env(\.|$)"),
    re.compile(r"(^|/)local\.properties$"),
)
SAFE_CLICK_PATTERN = re.compile(
    r"(?:验证码|付款|转账|确认下单|确认支付|confirm[^;\n]{0,40}(?:payment|order))"
    r"[^;\n]{0,240}(?:performClick\s*\(|dispatchGesture\s*\(|input\s+tap|\.click\s*\()",
    re.IGNORECASE,
)
SECRET_VALUE_PATTERN = re.compile(
    r"(?i)(?:api[_-]?key|secret|token|password)[^\n]{0,40}"
    r"(?:sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9]{32,})"
)


@dataclass
class CodeChange:
    """A proposed file change prepared by an agent."""

    file_path: str
    new_content: str
    original_content: str = ""


@dataclass
class EvalReport:
    """Structured result of one harness run."""

    passed: bool = True
    checks: dict = field(default_factory=dict)
    summary: str = ""

    def add(self, name: str, passed: bool, detail: object) -> None:
        self.checks[name] = {"passed": passed, "detail": detail}
        self.passed = self.passed and passed

    def failures(self):
        return [(name, item["detail"]) for name, item in self.checks.items() if not item["passed"]]


class CodeHarness:
    """Context, constraint, tool, evaluation, feedback, and cleanup layers."""

    def __init__(self, repo_path=".", command_timeout=900):
        self.repo = Path(repo_path).resolve()
        self.command_timeout = command_timeout

    # ===== 1. Context layer =====
    def build_context(self, task):
        changed = self.changed_files()
        return {
            "task": task,
            "repo": str(self.repo),
            "changed_files": changed,
            "relevant_files": self._relevant_files(task, changed),
            "constraints": self.constraints(),
            "project_rules": self._read_context_file("AGENTS.md"),
            "recent_changes": self._read_context_file("CHANGELOG.md", limit=4000),
        }

    def constraints(self):
        return {
            "version": "versionCode must increase by exactly one and versionName must advance semantically",
            "core_regression": "AgentEngine, DeviceTools, or accessibility changes require a regression test",
            "safety_redline": "never automatically click payment, transfer, order, or verification-code controls",
            "generality": "automation rules must work for any app/page; app profiles must be data-driven",
            "forbidden_outputs": ["_tmp/", "测试结果/", "*.jks", "*.keystore", ".env", "local.properties"],
            "banned_apis": ["eval(", "exec(", "os.system(", "powershell -EncodedCommand"],
            "max_changed_file_lines": 600,
        }

    def _relevant_files(self, task, changed):
        terms = [term.lower() for term in re.findall(r"[A-Za-z_][A-Za-z0-9_]{2,}", task)]
        candidates = list(dict.fromkeys(changed))
        candidates.extend(
            path.relative_to(self.repo).as_posix()
            for path in self._repo_files()
        )
        scored = []
        for candidate in dict.fromkeys(candidates):
            lowered = candidate.lower()
            score = sum(1 for term in terms if term in lowered)
            if Path(candidate).name.lower() in {"agentengine.kt", "devicetools.kt", "build.gradle"}:
                score += 1
            scored.append((score, candidate))
        scored.sort(key=lambda item: (-item[0], item[1]))
        return [name for score, name in scored if score > 0 or name in changed][:40]

    def _repo_files(self):
        paths = []
        for path in self.repo.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(self.repo)
            if any(part in EXCLUDED_DIRS for part in relative.parts):
                continue
            if path.suffix.lower() in CODE_SUFFIXES or path.name in {"AGENTS.md", "CHANGELOG.md"}:
                paths.append(path)
        return sorted(paths)

    def _read_context_file(self, name, limit=12000):
        path = self.repo / name
        if not path.exists():
            return ""
        return path.read_text(encoding="utf-8", errors="replace")[:limit]

    # ===== 2. Constraint layer =====
    def check_constraints(self, change):
        violations = []
        constraints = self.constraints()
        lines = change.new_content.splitlines()
        if len(lines) > constraints["max_changed_file_lines"]:
            violations.append(f"{change.file_path}: {len(lines)} lines > {constraints['max_changed_file_lines']}")
        for api in constraints["banned_apis"]:
            if api in change.new_content:
                violations.append(f"{change.file_path}: banned API {api}")
        if SAFE_CLICK_PATTERN.search(change.new_content):
            violations.append(
                f"{change.file_path}: possible automatic click on a payment/order/verification control"
            )
        if SECRET_VALUE_PATTERN.search(change.new_content):
            violations.append(f"{change.file_path}: possible embedded credential")
        return violations

    def evaluate_change_set(self, changed_files):
        violations = []
        for name in changed_files:
            if name in GUARDRAIL_SCAN_EXEMPT:
                continue
            path = self.repo / name
            if path.suffix.lower() not in {".py", ".kt", ".ps1"}:
                continue
            if not path.exists():
                violations.append(f"{name}: deleted file still needs a safety/content review")
                continue
            content = path.read_text(encoding="utf-8", errors="replace")
            violations.extend(self.check_constraints(CodeChange(name, content)))
        return violations

    # ===== 3. Tool layer =====
    def _run(self, args, cwd=None, timeout=None, env=None):
        try:
            result = subprocess.run(
                list(args),
                cwd=cwd or self.repo,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout or self.command_timeout,
                env=env,
            )
            output = (result.stdout + "\n" + result.stderr).strip()
            return {"passed": result.returncode == 0, "code": result.returncode, "output": output}
        except FileNotFoundError:
            return {"passed": False, "code": 127, "output": f"command not found: {args[0]}"}
        except subprocess.TimeoutExpired:
            limit = timeout or self.command_timeout
            return {"passed": False, "code": 124, "output": f"command timed out after {limit}s"}

    def _git(self, *args):
        result = self._run(["git", *args], timeout=30)
        if not result["passed"]:
            raise RuntimeError(result["output"])
        return result["output"]

    def gradle_command(self):
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        return [str(self.repo / wrapper)]

    def _gradle_env(self):
        configured = os.environ.get("JAVA_HOME")
        if configured and Path(configured).exists():
            return os.environ.copy()
        probe = self._run(
            ["java", "-XshowSettings:properties", "-version"],
            timeout=15,
        )
        match = re.search(r"java\.home\s*=\s*(.+)", probe["output"])
        if match:
            java_home = Path(match.group(1).strip())
            if java_home.exists():
                env = os.environ.copy()
                env["JAVA_HOME"] = str(java_home)
                return env
        return os.environ.copy()

    def run_tests(self):
        return self._run([*self.gradle_command(), "testDebugUnitTest"], env=self._gradle_env())

    def run_build(self):
        return self._run([*self.gradle_command(), "assembleDebug"], env=self._gradle_env())

    # ===== 4. Evaluation layer =====
    def changed_files(self):
        output = self._git("status", "--porcelain", "--untracked-files=all")
        names = []
        for line in output.splitlines():
            if not line or line.startswith("??"):
                continue
            name = line[3:].strip().strip('"').replace("\\", "/")
            if name:
                names.append(name)
        diff = self._git("diff", "--name-only", "HEAD")
        names.extend(line.strip().replace("\\", "/") for line in diff.splitlines() if line.strip())
        return list(dict.fromkeys(names))

    def staged_files(self):
        return [name for name in self._git("diff", "--cached", "--name-only").splitlines() if name]

    def versions(self, content):
        code_match = re.search(r"versionCode\s+(\d+)", content)
        name_match = re.search(r'versionName\s+"([^"]+)"', content)
        if not code_match or not name_match:
            raise ValueError("app/build.gradle must define versionCode and versionName")
        return int(code_match.group(1)), name_match.group(1)

    def check_version(self, changed_files):
        if APP_GRADLE.as_posix() not in changed_files:
            return False, "app/build.gradle was not changed; every code change needs a version bump"
        old_head = self._git("show", f"HEAD:{APP_GRADLE.as_posix()}")
        old_code, old_name = self.versions(old_head)
        new_code, new_name = self.versions((self.repo / APP_GRADLE).read_text(encoding="utf-8"))
        if new_code != old_code + 1:
            return False, f"versionCode must be {old_code + 1}, found {new_code}"
        if not self._is_valid_semantic_step(old_name, new_name):
            return False, f"invalid semantic version step: {old_name} -> {new_name}"
        return True, f"{old_code}/{old_name} -> {new_code}/{new_name}"

    @staticmethod
    def _is_valid_semantic_step(old, new):
        try:
            old_major, old_minor, old_patch = (int(value) for value in old.split("."))
            new_major, new_minor, new_patch = (int(value) for value in new.split("."))
        except ValueError:
            return False
        patch_bump = (new_major, new_minor, new_patch) == (old_major, old_minor, old_patch + 1)
        minor_bump = (new_major, new_minor, new_patch) == (old_major, old_minor + 1, 0)
        major_bump = (new_major, new_minor, new_patch) == (old_major + 1, 0, 0)
        return patch_bump or minor_bump or major_bump

    def check_forbidden_paths(self, changed_files):
        hits = [
            name for name in changed_files
            if any(pattern.search(name) for pattern in FORBIDDEN_PATH_PATTERNS)
        ]
        return not hits, hits or "no forbidden build/debug/secret artifacts"

    def check_core_regression(self, changed_files):
        core_changed = sorted(set(changed_files) & CORE_FILES)
        if not core_changed:
            return True, "core engine unchanged"
        tests_changed = [
            name for name in changed_files
            if name.startswith("app/src/test/") and name.endswith(".kt")
        ]
        return bool(tests_changed), {"core": core_changed, "tests": tests_changed}

    def evaluate(self, changed_files=None, run_verification=True):
        report = EvalReport()
        files = list(changed_files) if changed_files is not None else self.changed_files()
        if not files:
            report.add("changes", False, "no tracked or untracked changes found")
            report.summary = "FAIL: 0/1 checks passed"
            return report

        report.add("changes", True, files)
        violations = self.evaluate_change_set(files)
        report.add("constraints", not violations, violations or "no constraint violations")
        report.add("forbidden_paths", *self.check_forbidden_paths(files))
        report.add("core_regression", *self.check_core_regression(files))
        report.add("version", *self.check_version(files))

        if run_verification:
            test_result = self.run_tests()
            report.add("unit_tests", test_result["passed"], test_result["output"])
            build_result = self.run_build()
            report.add("debug_build", build_result["passed"], build_result["output"])
        else:
            report.add("verification", True, "skipped by caller")

        failed = len(report.failures())
        total = len(report.checks)
        report.summary = f"{'PASS' if report.passed else 'FAIL'}: {total - failed}/{total} checks passed"
        return report

    # ===== 5. Feedback layer =====
    def format_feedback(self, report):
        lines = ["Fix these harness failures before committing:"]
        for name, detail in report.failures():
            lines.append(f"- {name}: {detail}")
        return "\n".join(lines)

    def run_with_agent(self, task, agent_fn, max_iterations=3):
        context = self.build_context(task)
        last_error = None
        last_change = None
        for attempt in range(1, max_iterations + 1):
            last_change = agent_fn(context, last_error)
            violations = self.check_constraints(last_change)
            temporary_report = EvalReport(passed=not violations)
            temporary_report.add("constraints", not violations, violations or "no constraint violations")
            if not temporary_report.passed:
                last_error = self.format_feedback(temporary_report)
                continue
            final_report = self.evaluate(context["changed_files"])
            if final_report.passed:
                return {"status": "success", "change": last_change, "attempts": attempt, "report": final_report}
            last_error = self.format_feedback(final_report)
        return {
            "status": "failed",
            "change": last_change,
            "attempts": max_iterations,
            "last_error": last_error,
        }

    # ===== 6. Cleanup layer =====
    def cleanup_report(self):
        output = self._git("status", "--porcelain", "--untracked-files=all")
        rows = [line for line in output.splitlines() if line]
        suspicious = [
            row for row in rows
            if any(pattern.search(row[3:].replace("\\", "/")) for pattern in FORBIDDEN_PATH_PATTERNS)
        ]
        return {"passed": not suspicious, "rows": rows, "suspicious": suspicious}


def _print_json(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Android repository code-change harness")
    parser.add_argument("--repo", default=".", help="repository path")
    subparsers = parser.add_subparsers(dest="command", required=True)
    context_parser = subparsers.add_parser("context", help="print context for a task")
    context_parser.add_argument("task")
    check_parser = subparsers.add_parser("check", help="evaluate the current change set")
    check_parser.add_argument("--json", action="store_true", help="print the structured report")
    check_parser.add_argument("--skip-verification", action="store_true", help="do not run Gradle")
    subparsers.add_parser("cleanup", help="report forbidden working-tree artifacts")
    args = parser.parse_args(argv)

    harness = CodeHarness(args.repo)
    if args.command == "context":
        _print_json(harness.build_context(args.task))
        return 0
    if args.command == "cleanup":
        result = harness.cleanup_report()
        _print_json(result)
        return 0 if result["passed"] else 2

    report = harness.evaluate(run_verification=not args.skip_verification)
    if args.json:
        _print_json({
            "passed": report.passed,
            "summary": report.summary,
            "checks": report.checks,
        })
    else:
        print(report.summary)
        for name, detail in report.failures():
            print(f"[FAIL] {name}: {detail}")
    return 0 if report.passed else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(2)
