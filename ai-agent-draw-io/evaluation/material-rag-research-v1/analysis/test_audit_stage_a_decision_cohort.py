import copy
import json
import unittest
from pathlib import Path

from audit_stage_a_decision_cohort import (
    FIXTURE,
    GENERATION_TASKS,
    MANIFEST,
    audit_cohort,
    cohort_sha256,
    forbidden_families,
)


class StageADecisionCohortAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cohort = json.loads(FIXTURE.read_text(encoding="utf-8"))
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        generation = json.loads(GENERATION_TASKS.read_text(encoding="utf-8"))
        cls.reserved = forbidden_families(manifest, generation)

    @staticmethod
    def approved_review(cohort):
        reviewer = {"reviewerId": "independent-reviewer", "kind": "human"}
        review = {
            "schemaVersion": "material-rag-stage-a-independent-review-v1",
            "cohortId": cohort["cohortId"],
            "cohortSha256": cohort_sha256(cohort),
            "reviewer": reviewer,
            "reviewReport": "stage-a-independent-review-report-v1.json",
            "caseDecisions": [
                {"caseId": case["caseId"], "decision": "approve"}
                for case in cohort["cases"]
            ],
        }
        report = {
            "schemaVersion": "material-rag-stage-a-independent-review-report-v1",
            "cohortId": cohort["cohortId"],
            "cohortSha256": cohort_sha256(cohort),
            "reviewer": reviewer,
            "result": "approve",
        }
        return review, report

    def test_draft_cohort_is_structurally_ready_but_not_frozen(self):
        result = audit_cohort(self.cohort, self.reserved)
        self.assertTrue(result["structuralReady"])
        self.assertEqual("review_pending", result["status"])
        self.assertEqual(30, result["counts"]["cases"])
        self.assertEqual(14, result["counts"]["blockedCases"])
        self.assertEqual(
            {
                "Ready": 12,
                "InsufficientEvidence": 6,
                "ClarificationNeeded": 4,
                "DegradedDependency": 4,
                "NotRequired": 4,
            },
            result["counts"]["outcomes"],
        )

    def test_independent_review_status_is_required_for_ready(self):
        frozen = copy.deepcopy(self.cohort)
        frozen["status"] = "frozen_independently_reviewed"
        result = audit_cohort(frozen, self.reserved)
        self.assertEqual("review_pending", result["status"])
        self.assertFalse(result["independentReviewComplete"])

    def test_complete_independent_review_ledger_allows_ready(self):
        frozen = copy.deepcopy(self.cohort)
        frozen["status"] = "frozen_independently_reviewed"
        review, report = self.approved_review(frozen)
        result = audit_cohort(frozen, self.reserved, review, report)
        self.assertEqual("ready", result["status"])
        self.assertTrue(result["independentReviewComplete"])

    def test_review_ledger_cannot_be_reused_after_fixture_change(self):
        frozen = copy.deepcopy(self.cohort)
        frozen["status"] = "frozen_independently_reviewed"
        review, report = self.approved_review(frozen)
        frozen["cases"][0]["request"] += " Changed after review."
        result = audit_cohort(frozen, self.reserved, review, report)
        self.assertEqual("review_pending", result["status"])
        self.assertIn("independent review ledger cohort hash does not match", result["reviewErrors"])

    def test_distribution_tampering_fails_closed(self):
        changed = copy.deepcopy(self.cohort)
        changed["cases"][0]["expectedOutcome"] = "NotRequired"
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertTrue(any("distribution mismatch" in error for error in result["errors"]))

    def test_blocked_case_cannot_allow_mutation(self):
        changed = copy.deepcopy(self.cohort)
        changed["cases"][12]["expectedCanvasMutation"] = "allowed"
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertTrue(any("must block canvas mutation" in error for error in result["errors"]))

    def test_existing_document_family_overlap_fails_closed(self):
        changed = copy.deepcopy(self.cohort)
        changed["cases"][0]["documentFamily"] = next(iter(self.reserved))
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertTrue(any("overlaps an existing corpus" in error for error in result["errors"]))

    def test_not_required_cannot_declare_material_retrieval(self):
        changed = copy.deepcopy(self.cohort)
        changed["cases"][-1]["expectedRetrieval"] = "completed"
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertTrue(any("NotRequired must have no retrieval" in error for error in result["errors"]))

    def test_material_case_without_executable_setup_fails_closed(self):
        changed = copy.deepcopy(self.cohort)
        del changed["setupCatalog"]["caseSetups"]["sta-ready-01"]
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertIn("sta-ready-01: material-backed case requires an executable setup", result["errors"])

    def test_setup_anchor_must_resolve_in_its_declared_source(self):
        changed = copy.deepcopy(self.cohort)
        changed["setupCatalog"]["caseSetups"]["sta-ready-01"]["requiredAnchorIds"] = ["invented-anchor"]
        result = audit_cohort(changed, self.reserved)
        self.assertFalse(result["structuralReady"])
        self.assertIn("sta-ready-01: requiredAnchorIds must resolve in setup sources", result["errors"])


if __name__ == "__main__":
    unittest.main()
