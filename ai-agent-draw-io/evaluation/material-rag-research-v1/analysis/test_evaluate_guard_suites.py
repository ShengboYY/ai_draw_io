"""Tests for the material-RAG guard fixture-contract runner."""

from __future__ import annotations

import unittest
from pathlib import Path

from evaluate_guard_suites import execute


ROOT = Path(__file__).resolve().parents[1]


class EvaluateGuardSuitesTest(unittest.TestCase):

    def test_all_guard_contracts_and_minimums_pass(self) -> None:
        result = execute(ROOT)

        self.assertEqual("passed", result["status"])
        self.assertTrue(all(value["minimumMet"] for value in result["suites"].values()))
        self.assertTrue(all(value["failedCases"] == 0 for value in result["suites"].values()))
        self.assertEqual("fixture-contract", result["executionLevel"])


if __name__ == "__main__":
    unittest.main()
