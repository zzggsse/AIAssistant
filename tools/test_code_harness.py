import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))

from code_harness import CodeChange, CodeHarness, EvalReport


class CodeHarnessTest(unittest.TestCase):
    def setUp(self):
        self.harness = CodeHarness(".")

    def test_constraints_block_dynamic_execution_and_secret(self):
        violations = self.harness.check_constraints(CodeChange(
            "tools/example.py",
            "value = eval('1 + 1')\nAPI_KEY = 'sk-abcdefghijklmnopqrstuvwx'\n",
        ))
        self.assertEqual(len(violations), 2)
        self.assertIn("eval(", violations[0])

    def test_constraints_block_high_risk_click(self):
        violations = self.harness.check_constraints(CodeChange(
            "app/src/main/java/com/aibutler/app/DeviceTools.kt",
            "if (label.contains(\"验证码\")) node.performClick()\n",
        ))
        self.assertEqual(len(violations), 1)
        self.assertIn("payment/order/verification", violations[0])

    def test_semantic_version_accepts_each_allowed_step(self):
        self.assertTrue(CodeHarness._is_valid_semantic_step("1.12.4", "1.12.5"))
        self.assertTrue(CodeHarness._is_valid_semantic_step("1.12.4", "1.13.0"))
        self.assertTrue(CodeHarness._is_valid_semantic_step("1.12.4", "2.0.0"))
        self.assertFalse(CodeHarness._is_valid_semantic_step("1.12.4", "1.12.6"))
        self.assertFalse(CodeHarness._is_valid_semantic_step("1.12.4", "1.12.4"))

    def test_feedback_lists_all_failed_checks(self):
        report = EvalReport()
        report.add("constraints", False, ["bad API"])
        report.add("version", False, "versionCode did not advance")
        feedback = self.harness.format_feedback(report)
        self.assertIn("- constraints: ['bad API']", feedback)
        self.assertIn("- version: versionCode did not advance", feedback)


if __name__ == "__main__":
    unittest.main()
