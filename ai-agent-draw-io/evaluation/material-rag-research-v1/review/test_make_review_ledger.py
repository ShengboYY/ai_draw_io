"""Tests for auditable human-review imports used by the corpus ledger."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from make_review_ledger import load_human_review, load_owner_spot_check


class HumanReviewImportTest(unittest.TestCase):

    def test_loads_complete_per_case_human_decisions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            path.write_text(json.dumps({
                "schemaVersion": "material-rag-human-review-v1",
                "reviewerId": "reviewer-1",
                "cases": [
                    {"caseId": "case-a", "verdict": "accept", "notes": "Checked evidence."},
                    {"caseId": "case-b", "verdict": "needs_fix", "notes": "Wrong page."},
                ],
            }), encoding="utf-8")

            review = load_human_review(path, {"case-a", "case-b"})

        self.assertEqual("reviewer-1", review["reviewerId"])
        self.assertEqual("accept", review["decisions"]["case-a"]["verdict"])
        self.assertEqual(64, len(review["artifact"]["sha256"]))

    def test_rejects_name_only_or_partial_human_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            path.write_text(json.dumps({
                "schemaVersion": "material-rag-human-review-v1",
                "reviewerId": "reviewer-1",
                "cases": [{"caseId": "case-a", "verdict": "accept", "notes": "Checked."}],
            }), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "cover every core case"):
                load_human_review(path, {"case-a", "case-b"})

    def test_rejects_a_needs_fix_decision_without_notes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            path.write_text(json.dumps({
                "schemaVersion": "material-rag-human-review-v1",
                "reviewerId": "reviewer-1",
                "cases": [{"caseId": "case-a", "verdict": "needs_fix", "notes": ""}],
            }), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "notes"):
                load_human_review(path, {"case-a"})


class OwnerSpotCheckImportTest(unittest.TestCase):

    def test_loads_an_approved_frozen_representative_sample(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = root / "policy.json"
            artifact = root / "spot-check.json"
            policy.write_text(json.dumps({
                "schemaVersion": "material-rag-owner-spot-check-policy-v1",
                "reviewMethod": "automated-full-owner-spot-check-v1",
                "requiredCoreCaseIds": ["case-visual", "case-no-answer"],
                "requiredGenerationTaskIds": ["task-edit", "task-scan"],
            }), encoding="utf-8")
            artifact.write_text(json.dumps({
                "schemaVersion": "material-rag-owner-spot-check-v1",
                "reviewerId": "project-owner",
                "decision": "approve",
                "confirmedCoreCaseIds": ["case-visual", "case-no-answer"],
                "confirmedGenerationTaskIds": ["task-edit", "task-scan"],
                "confirmationNote": "Approved after representative cases were displayed.",
            }), encoding="utf-8")

            review = load_owner_spot_check(
                artifact, policy,
                {"case-visual", "case-no-answer"},
                {"task-edit", "task-scan"},
            )

        self.assertEqual("project-owner", review["reviewerId"])
        self.assertEqual("automated-full-owner-spot-check-v1", review["reviewMethod"])
        self.assertEqual(64, len(review["artifact"]["sha256"]))

    def test_rejects_unknown_or_missing_required_samples(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = root / "policy.json"
            artifact = root / "spot-check.json"
            policy.write_text(json.dumps({
                "schemaVersion": "material-rag-owner-spot-check-policy-v1",
                "reviewMethod": "automated-full-owner-spot-check-v1",
                "requiredCoreCaseIds": ["case-visual"],
                "requiredGenerationTaskIds": ["task-edit", "task-scan"],
            }), encoding="utf-8")
            artifact.write_text(json.dumps({
                "schemaVersion": "material-rag-owner-spot-check-v1",
                "reviewerId": "project-owner",
                "decision": "approve",
                "confirmedCoreCaseIds": ["case-visual"],
                "confirmedGenerationTaskIds": ["task-edit", "unknown-task"],
                "confirmationNote": "Incomplete sample.",
            }), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "required generation sample"):
                load_owner_spot_check(
                    artifact, policy, {"case-visual"}, {"task-edit", "task-scan"}
                )


if __name__ == "__main__":
    unittest.main()
