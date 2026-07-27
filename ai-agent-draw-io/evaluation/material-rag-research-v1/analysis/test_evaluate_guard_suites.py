"""Tests for the material-RAG guard fixture-contract runner."""

from __future__ import annotations

import unittest
from pathlib import Path

from evaluate_guard_suites import case_errors, execute


ROOT = Path(__file__).resolve().parents[1]


class EvaluateGuardSuitesTest(unittest.TestCase):

    def test_all_guard_contracts_and_minimums_pass(self) -> None:
        result = execute(ROOT)

        self.assertEqual("passed", result["status"])
        self.assertTrue(all(value["minimumMet"] for value in result["suites"].values()))
        self.assertTrue(all(value["failedCases"] == 0 for value in result["suites"].values()))
        self.assertEqual("fixture-contract", result["executionLevel"])

    def test_authorization_case_without_decision_context_is_rejected(self) -> None:
        case = {
            "caseId": "bad-auth", "split": "guard_authorization", "answerable": True,
            "primaryCategory": "versionAndAuthorization", "expectedAnswer": "Denied.",
            "allowedSourceVersions": ["source:v1"], "goldAnchorIds": ["anchor"],
        }

        errors = case_errors(
            case, {"anchor": {"split": "guard_authorization"}}, {"source:v1"}
        )

        self.assertIn("authorization case lacks an authorization decision context", errors)

    def test_unknown_source_and_cross_suite_anchor_are_rejected(self) -> None:
        case = {
            "caseId": "bad-source", "split": "guard_failure", "answerable": True,
            "primaryCategory": "exactLookup", "expectedAnswer": "Value.",
            "allowedSourceVersions": ["missing:v1"], "goldAnchorIds": ["anchor"],
        }

        errors = case_errors(
            case, {"anchor": {"split": "guard_versioning"}}, {"source:v1"}
        )

        self.assertIn("allowed source version is missing or unknown", errors)
        self.assertIn("gold anchor crosses suite: anchor", errors)


if __name__ == "__main__":
    unittest.main()
