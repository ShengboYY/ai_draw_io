"""Tests for deterministic E0 corpus-readiness auditing."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from audit_corpus import (audit, evidence_shape_errors, generation_context_errors,
                          generation_task_errors, reviewed_case_ids, sha256)


ROOT = Path(__file__).resolve().parents[1]
REVIEW_LEDGER = ROOT / "review/review-ledger.json"
LOCK = ROOT / "fixtures/generated/corpus-lock.json"


class AuditCorpusTest(unittest.TestCase):

    def test_current_corpus_freezes_with_automated_full_review_and_owner_spot_check(self) -> None:
        result, lock = audit(ROOT, REVIEW_LEDGER)
        plan = json.loads((ROOT / "experiment-plan-v2.json").read_text(encoding="utf-8"))

        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(sum(plan["coreCases"].values()), result["counts"]["coreCases"])
        self.assertEqual(dict(sorted(plan["coreCases"].items())), result["counts"]["coreBySplit"])
        self.assertTrue(result["readyForE0Freeze"])
        self.assertEqual("owner_spot_checked", result["gaps"]["reviewGovernance"])
        self.assertEqual("not_claimed", result["gaps"]["independentHumanReview"])
        self.assertEqual("frozen", lock["status"])

    def test_without_review_ledger_the_lock_remains_a_candidate(self) -> None:
        result, lock = audit(ROOT, None)

        self.assertFalse(result["readyForE0Freeze"])
        self.assertEqual("candidate", lock["status"])

    def test_committed_lock_matches_a_fresh_reviewed_audit(self) -> None:
        _, expected_lock = audit(ROOT, REVIEW_LEDGER)
        committed_lock = json.loads(LOCK.read_text(encoding="utf-8"))

        self.assertEqual(expected_lock, committed_lock)

    def test_review_ledger_requires_two_distinct_human_reviewers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            human_1 = Path(directory) / "human-1.json"
            human_2 = Path(directory) / "human-2.json"
            human_1.write_text(
                json.dumps({
                    "schemaVersion": "material-rag-human-review-v1", "reviewerId": "human-1",
                    "cases": [
                        {"caseId": case_id, "verdict": "accept", "notes": ""}
                        for case_id in ("accepted", "ai-plus-one-human", "one-reviewer",
                                        "duplicate-reviewer", "not-agreed")
                    ],
                }),
                encoding="utf-8",
            )
            human_2.write_text(
                json.dumps({
                    "schemaVersion": "material-rag-human-review-v1", "reviewerId": "human-2",
                    "cases": [
                        {"caseId": case_id, "verdict": "accept", "notes": ""}
                        for case_id in ("accepted", "ai-plus-one-human", "one-reviewer",
                                        "duplicate-reviewer", "not-agreed")
                    ],
                }),
                encoding="utf-8",
            )
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v2",
                "reviewerRegistry": {
                    "ai": {"kind": "ai"},
                    "human-1": {"kind": "human"},
                    "human-2": {"kind": "human"},
                },
                "humanReviewArtifacts": [
                    {"reviewerId": "human-1", "path": human_1.name, "sha256": sha256(human_1)},
                    {"reviewerId": "human-2", "path": human_2.name, "sha256": sha256(human_2)},
                ],
                "cases": [
                    {"caseId": "accepted", "status": "agreed",
                     "reviewers": ["human-1", "human-2"]},
                    {"caseId": "ai-plus-one-human", "status": "agreed",
                     "reviewers": ["ai", "human-1"]},
                    {"caseId": "one-reviewer", "status": "agreed",
                     "reviewers": ["human-1"]},
                    {"caseId": "duplicate-reviewer", "status": "agreed",
                     "reviewers": ["human-1", "human-1"]},
                    {"caseId": "not-agreed", "status": "pending",
                     "reviewers": ["human-1", "human-2"]},
                ],
            }), encoding="utf-8")

            reviewed, status, ledger_sha = reviewed_case_ids(ledger)

        self.assertEqual({"accepted"}, reviewed)
        self.assertEqual("double_reviewed", status)
        self.assertEqual(64, len(ledger_sha or ""))

    def test_ai_plus_one_human_is_not_reported_as_double_human_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            human = Path(directory) / "human.json"
            human.write_text(
                json.dumps({
                    "schemaVersion": "material-rag-human-review-v1", "reviewerId": "human",
                    "cases": [{"caseId": "case-1", "verdict": "accept", "notes": ""}],
                }),
                encoding="utf-8",
            )
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v2",
                "reviewerRegistry": {
                    "ai": {"kind": "ai"},
                    "human": {"kind": "human"},
                },
                "humanReviewArtifacts": [{
                    "reviewerId": "human", "path": human.name, "sha256": sha256(human),
                }],
                "cases": [{
                    "caseId": "case-1",
                    "status": "agreed",
                    "reviewers": ["ai", "human"],
                }],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(ledger)

        self.assertEqual(set(), reviewed)
        self.assertEqual("single_human_reviewed", status)

    def test_legacy_untyped_review_ledger_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v1",
                "cases": [{
                    "caseId": "case-1",
                    "status": "agreed",
                    "reviewers": ["reviewer-1", "reviewer-2"],
                }],
            }), encoding="utf-8")

            reviewed, status, ledger_sha = reviewed_case_ids(ledger)

        self.assertEqual(set(), reviewed)
        self.assertEqual("pending", status)
        self.assertEqual(64, len(ledger_sha or ""))

    def test_human_artifact_without_per_case_decisions_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            artifacts = []
            for reviewer in ("human-1", "human-2"):
                path = Path(directory) / f"{reviewer}.json"
                path.write_text(json.dumps({
                    "schemaVersion": "material-rag-human-review-v1",
                    "reviewerId": reviewer,
                    "cases": [],
                }), encoding="utf-8")
                artifacts.append({
                    "reviewerId": reviewer, "path": path.name, "sha256": sha256(path),
                })
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v2",
                "reviewerRegistry": {
                    "human-1": {"kind": "human"}, "human-2": {"kind": "human"},
                },
                "humanReviewArtifacts": artifacts,
                "cases": [{
                    "caseId": "case-1", "status": "agreed",
                    "reviewers": ["human-1", "human-2"],
                }],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(ledger)

        self.assertEqual(set(), reviewed)
        self.assertEqual("pending", status)

    def test_partial_human_artifacts_do_not_satisfy_a_complete_core_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            artifacts = []
            for reviewer in ("human-1", "human-2"):
                path = Path(directory) / f"{reviewer}.json"
                path.write_text(json.dumps({
                    "schemaVersion": "material-rag-human-review-v1",
                    "reviewerId": reviewer,
                    "cases": [{"caseId": "case-1", "verdict": "accept", "notes": ""}],
                }), encoding="utf-8")
                artifacts.append({
                    "reviewerId": reviewer, "path": path.name, "sha256": sha256(path),
                })
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v2",
                "reviewerRegistry": {
                    "human-1": {"kind": "human"}, "human-2": {"kind": "human"},
                },
                "humanReviewArtifacts": artifacts,
                "cases": [{
                    "caseId": "case-1", "status": "agreed",
                    "reviewers": ["human-1", "human-2"],
                }],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(
                ledger, expected_case_ids={"case-1", "case-2"}
            )

        self.assertEqual(set(), reviewed)
        self.assertEqual("pending", status)

    def test_owner_spot_check_unlocks_only_the_frozen_representative_sample(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = root / "owner-spot-check-policy-v1.json"
            artifact = root / "owner-spot-check.json"
            ledger = root / "review-ledger.json"
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
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v3",
                "reviewMethod": "automated-full-owner-spot-check-v1",
                "reviewerRegistry": {
                    "automated-review": {"kind": "automated"},
                    "project-owner": {"kind": "human_project_owner"},
                },
                "ownerSpotCheckArtifact": {
                    "reviewerId": "project-owner",
                    "path": artifact.name,
                    "sha256": sha256(artifact),
                    "policyPath": policy.name,
                    "policySha256": sha256(policy),
                },
                "cases": [
                    {"caseId": "case-visual", "status": "agreed",
                     "reviewers": ["automated-review"]},
                    {"caseId": "case-no-answer", "status": "agreed",
                     "reviewers": ["automated-review"]},
                ],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(
                ledger,
                expected_case_ids={"case-visual", "case-no-answer"},
                owner_spot_check_policy=policy,
                expected_generation_task_ids={"task-edit", "task-scan"},
            )

        self.assertEqual({"case-visual", "case-no-answer"}, reviewed)
        self.assertEqual("owner_spot_checked", status)

    def test_owner_spot_check_fails_closed_when_a_required_task_was_not_confirmed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = root / "owner-spot-check-policy-v1.json"
            artifact = root / "owner-spot-check.json"
            ledger = root / "review-ledger.json"
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
                "confirmedGenerationTaskIds": ["task-edit"],
                "confirmationNote": "Incomplete sample.",
            }), encoding="utf-8")
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v3",
                "reviewMethod": "automated-full-owner-spot-check-v1",
                "reviewerRegistry": {
                    "automated-review": {"kind": "automated"},
                    "project-owner": {"kind": "human_project_owner"},
                },
                "ownerSpotCheckArtifact": {
                    "reviewerId": "project-owner", "path": artifact.name,
                    "sha256": sha256(artifact), "policyPath": policy.name,
                    "policySha256": sha256(policy),
                },
                "cases": [{"caseId": "case-visual", "status": "agreed",
                           "reviewers": ["automated-review"]}],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(
                ledger,
                expected_case_ids={"case-visual"},
                owner_spot_check_policy=policy,
                expected_generation_task_ids={"task-edit", "task-scan"},
            )

        self.assertEqual(set(), reviewed)
        self.assertEqual("pending", status)

    def test_human_reviewer_without_a_hash_verified_artifact_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = Path(directory) / "review-ledger.json"
            ledger.write_text(json.dumps({
                "schemaVersion": "material-rag-review-ledger-v2",
                "reviewerRegistry": {
                    "human-1": {"kind": "human"},
                    "human-2": {"kind": "human"},
                },
                "cases": [{
                    "caseId": "case-1",
                    "status": "agreed",
                    "reviewers": ["human-1", "human-2"],
                }],
            }), encoding="utf-8")

            reviewed, status, _ = reviewed_case_ids(ledger)

        self.assertEqual(set(), reviewed)
        self.assertEqual("pending", status)

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


class GenerationTaskAuditTest(unittest.TestCase):

    def test_rejects_conflicting_generation_source_scope_contracts(self) -> None:
        base = {
            "split": "development",
            "sourceVersion": "source-a:v1",
            "requiredAnchors": ["anchor-a"],
            "citationAssertions": {"mustCiteAnchors": ["anchor-a"]},
            "claimAssertions": {"requiredClaims": [{
                "claimId": "claim-1", "description": "Claim", "requiresCitation": True,
            }]},
        }
        tasks = [
            {**base, "taskId": "selected", "sourceScopeMode": "selected_only"},
            {**base, "taskId": "automatic", "sourceScopeMode": "chartbook_auto",
             "selectedMaterialVersion": "source-a:v1"},
        ]
        anchors = {"anchor-a": {
            "source": "source-a", "version": "v1", "split": "development",
        }}

        errors = generation_task_errors(tasks, anchors, {"source-a:v1"})

        self.assertEqual([
            "selected-only task has no matching selected material version",
            "automatic task must not declare a selected material version",
        ], [error["error"] for error in errors])

    def test_rejects_edit_task_without_input_or_preservation_assertions(self) -> None:
        task = {
            "taskId": "task-1", "split": "development", "type": "structural_edit",
            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"],
            "citationAssertions": {"mustCiteAnchors": ["anchor-a"]},
            "claimAssertions": {"requiredClaims": [{"claimId": "claim-1", "description": "Claim",
                                                       "requiresCitation": True}]},
        }
        anchors = {"anchor-a": {"source": "source-a", "version": "v1", "split": "development"}}

        errors = generation_task_errors([task], anchors, {"source-a:v1"})

        self.assertEqual(["edit task has no input XML", "edit task has no edit assertions"],
                         [error["error"] for error in errors])

    def test_rejects_anchor_from_another_source_or_split(self) -> None:
        task = {
            "taskId": "task-1",
            "split": "development",
            "sourceVersion": "source-a:v1",
            "requiredAnchors": ["anchor-b"],
            "citationAssertions": {"mustCiteAnchors": ["anchor-b"]},
        }
        anchors = {"anchor-b": {"source": "source-b", "version": "v1", "split": "validation"}}

        errors = generation_task_errors([task], anchors, {"source-a:v1", "source-b:v1"})

        self.assertEqual("anchor source or split mismatch", errors[0]["error"])

    def test_rejects_divergent_required_and_citation_anchors(self) -> None:
        task = {
            "taskId": "task-1",
            "split": "development",
            "sourceVersion": "source-a:v1",
            "requiredAnchors": ["anchor-a"],
            "citationAssertions": {"mustCiteAnchors": []},
        }
        anchors = {"anchor-a": {"source": "source-a", "version": "v1", "split": "development"}}

        errors = generation_task_errors([task], anchors, {"source-a:v1"})

        self.assertEqual("citation anchors differ from required anchors", errors[0]["error"])

    def test_rejects_a_no_retrieval_task_with_material_citation_requirements(self) -> None:
        task = {
            "taskId": "task-1", "split": "development", "type": "layout_only_edit",
            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"],
            "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]},
            "claimAssertions": {"requiredClaims": [{"claimId": "claim-1", "description": "Claim",
                                                       "requiresCitation": True}]},
        }
        anchors = {"anchor-a": {"source": "source-a", "version": "v1", "split": "development"}}

        errors = generation_task_errors([task], anchors, {"source-a:v1"}, {"task-1"})

        self.assertIn("invalid no-retrieval task contract", [error["error"] for error in errors])

    def test_rejects_context_with_an_anchor_outside_the_task(self) -> None:
        tasks = {"task-1": {"taskId": "task-1", "split": "development",
                            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"]}}
        contexts = [{"taskId": "task-1", "arm": "fixed", "evidence": [{
            "anchorId": "anchor-b", "sourceVersion": "source-a:v1", "page": 1, "text": "evidence",
        }]}]
        anchors = {
            "anchor-a": {"source": "source-a", "version": "v1", "page": 1},
            "anchor-b": {"source": "source-a", "version": "v1", "page": 1},
        }

        errors = generation_context_errors(contexts, tasks, anchors, ROOT)

        self.assertEqual("context anchors differ from task anchors", errors[0]["error"])

    def test_rejects_missing_development_task_context(self) -> None:
        tasks = {"task-1": {"taskId": "task-1", "split": "development",
                            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"]}}

        errors = generation_context_errors([], tasks, {}, ROOT)

        self.assertEqual("development task contexts are incomplete", errors[0]["error"])

    def test_rejects_visual_artifact_without_matching_hash(self) -> None:
        tasks = {"task-1": {"taskId": "task-1", "split": "development",
                            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"]}}
        anchors = {"anchor-a": {"source": "source-a", "version": "v1", "page": 1}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "fixtures" / "generated" / "images" / "visual.png"
            image.parent.mkdir(parents=True)
            image.write_bytes(b"synthetic image")
            contexts = [{"taskId": "task-1", "arm": "fixed", "evidence": [{
                "anchorId": "anchor-a", "sourceVersion": "source-a:v1", "page": 1,
                "text": "evidence", "imagePath": "fixtures/generated/images/visual.png",
                "imageSha256": "wrong",
            }]}]

            errors = generation_context_errors(contexts, tasks, anchors, root)

        self.assertEqual("visual artifact hash mismatch", errors[0]["error"])

    def test_rejects_visual_artifact_outside_frozen_directory(self) -> None:
        tasks = {"task-1": {"taskId": "task-1", "split": "development",
                            "sourceVersion": "source-a:v1", "requiredAnchors": ["anchor-a"]}}
        anchors = {"anchor-a": {"source": "source-a", "version": "v1", "page": 1}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root / "outside.png"
            outside.write_bytes(b"synthetic image")
            contexts = [{"taskId": "task-1", "arm": "fixed", "evidence": [{
                "anchorId": "anchor-a", "sourceVersion": "source-a:v1", "page": 1,
                "text": "evidence", "imagePath": "outside.png", "imageSha256": sha256(outside),
            }]}]

            errors = generation_context_errors(contexts, tasks, anchors, root)

        self.assertEqual("visual artifact outside frozen directory", errors[0]["error"])


if __name__ == "__main__":
    unittest.main()
