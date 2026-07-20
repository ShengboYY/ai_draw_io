"""Tests for deterministic E0 corpus-readiness auditing."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from audit_corpus import audit, evidence_shape_errors, reviewed_case_ids


ROOT = Path(__file__).resolve().parents[1]


class AuditCorpusTest(unittest.TestCase):

    def test_current_corpus_is_structurally_valid_but_not_e0_ready(self) -> None:
        result, lock = audit(ROOT, None)

        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(240, result["counts"]["coreCases"])
        self.assertEqual({"development": 120, "holdout": 60, "validation": 60},
                         result["counts"]["coreBySplit"])
        self.assertFalse(result["readyForE0Freeze"])
        self.assertEqual("candidate", lock["status"])

    def test_committed_candidate_lock_matches_a_fresh_audit(self) -> None:
        _, expected_lock = audit(ROOT, None)
        committed_lock = json.loads(
            (ROOT / "fixtures/generated/corpus-lock.candidate.json").read_text(encoding="utf-8")
        )

        self.assertEqual(expected_lock, committed_lock)

    def test_review_ledger_requires_two_distinct_reviewers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            ledger.write_text(json.dumps({
                "cases": [
                    {"caseId": "accepted", "status": "agreed", "reviewers": ["r1", "r2"]},
                    {"caseId": "one-reviewer", "status": "agreed", "reviewers": ["r1"]},
                    {"caseId": "duplicate-reviewer", "status": "agreed", "reviewers": ["r1", "r1"]},
                    {"caseId": "not-agreed", "status": "pending", "reviewers": ["r1", "r2"]},
                ],
            }), encoding="utf-8")

            reviewed, status, ledger_sha = reviewed_case_ids(ledger)

        self.assertEqual({"accepted"}, reviewed)
        self.assertEqual("double_reviewed", status)
        self.assertEqual(64, len(ledger_sha or ""))

    def test_answerable_case_without_evidence_is_rejected(self) -> None:
        case = {
            "answerable": True,
            "goldAnchorIds": [],
            "requiredEvidenceGroups": [],
        }

        self.assertEqual([
            "answerable case has no gold anchors",
            "answerable case has no evidence groups",
        ], evidence_shape_errors(case))

    def test_invalid_operator_and_empty_group_are_rejected(self) -> None:
        case = {
            "answerable": True,
            "goldAnchorIds": ["anchor-1"],
            "requiredEvidenceGroups": [{"operator": "ALL", "evidence": []}],
        }

        self.assertEqual([
            "unsupported evidence-group operator",
            "evidence group is empty",
        ], evidence_shape_errors(case))


if __name__ == "__main__":
    unittest.main()
