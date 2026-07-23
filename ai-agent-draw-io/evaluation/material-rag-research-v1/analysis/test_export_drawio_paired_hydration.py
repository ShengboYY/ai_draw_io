import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("export_drawio_paired_hydration.py")
SPEC = importlib.util.spec_from_file_location("paired_hydration", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def task(task_id: str, source: str, anchor: str) -> dict:
    """Keep test fixtures small while exercising the full task contract."""
    return {"taskId": task_id, "split": "development", "sourceVersion": source,
            "allowedSourceVersions": [source, "shared:v1"], "requiredAnchors": [anchor],
            "citationAssertions": {"mustCiteAnchors": [anchor]}}


def candidate(chunk_id: str, source: str, anchor: str, rank: int) -> dict:
    text = f"Evidence {anchor}."
    return {"chunkId": chunk_id, "sourceVersion": source, "rank": rank,
            "page": 1, "retrievalTextSha256": hashlib.sha256(text.encode()).hexdigest(),
            "evidence": [{"anchorId": anchor, "sourceVersion": source, "page": 1, "text": text}]}


class PairedHydrationExportTest(unittest.TestCase):
    def test_exports_one_control_and_candidate_context_per_task(self):
        tasks = [task("a", "one:v1", "anchor-a"), task("b", "two:v1", "anchor-b")]
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [
                     {"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1),
                         candidate("a2", "one:v1", "anchor-a", 2), candidate("a3", "shared:v1", "anchor-a", 3)]},
                     {"taskId": "b", "candidates": [candidate("b1", "two:v1", "anchor-b", 1),
                         candidate("b2", "two:v1", "anchor-b", 2), candidate("b3", "shared:v1", "anchor-b", 3)]},
                 ]}
        result = MODULE.export(trace, tasks, "development", 2, 0.2, Path(__file__).parents[1],
                               candidate_pool_size=3)
        self.assertTrue(result["effectiveExperiment"])
        self.assertEqual(4, len(result["contexts"]))
        self.assertEqual(["a1", "a2"], result["taskSummaries"][0]["controlChunkIds"])
        self.assertEqual(["a1", "a3"], result["taskSummaries"][0]["candidateChunkIds"])

    def test_rejects_out_of_scope_evidence_and_no_op(self):
        one_task = [task("a", "one:v1", "anchor-a")]
        base = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"}}
        with self.assertRaisesRegex(ValueError, "out-of-scope"):
            MODULE.export({**base, "tasks": [{"taskId": "a", "candidates": [
                candidate("a1", "one:v1", "anchor-a", 1),
                candidate("a2", "other:v1", "anchor-a", 2)]}]}, one_task, "development", 2, 0.2,
                          Path.cwd(), candidate_pool_size=2)
        with self.assertRaisesRegex(ValueError, "effectiveness"):
            MODULE.export({**base, "tasks": [{"taskId": "a", "candidates": [
                candidate("a1", "one:v1", "anchor-a", 1),
                candidate("a2", "one:v1", "anchor-a", 2)]}]}, one_task, "development", 2, 0.2,
                          Path.cwd(), candidate_pool_size=2)

    def test_rejects_a_visual_artifact_with_the_wrong_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "route.png").write_bytes(b"actual image")
            visual = candidate("a1", "one:v1", "anchor-a", 1)
            visual["evidence"][0].update({"imagePath": "route.png", "imageSha256": "not-a-hash"})
            trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                     "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                     "tasks": [{"taskId": "a", "candidates": [visual]}]}
            with self.assertRaisesRegex(ValueError, "image hash mismatch"):
                MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0, root,
                              candidate_pool_size=1)

    def test_rejects_an_anchor_with_mismatched_frozen_provenance(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1)]}]}
        anchors = {"anchor-a": {"source": "one", "version": "v1", "page": 2, "split": "development"}}
        with self.assertRaisesRegex(ValueError, "anchor provenance mismatch"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                          Path.cwd(), anchors=anchors, candidate_pool_size=1)

    def test_keeps_retrieved_chunk_evidence_without_an_evaluator_anchor(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                "tasks": [{"taskId": "a", "candidates": [{
                     "chunkId": "a1", "sourceVersion": "one:v1", "rank": 1, "page": 1,
                     "retrievalTextSha256": hashlib.sha256(
                         b"Retrieved but not evaluator-gold text.").hexdigest(),
                     "evidence": [{"anchorId": "retrieved:a1", "sourceVersion": "one:v1",
                                   "page": 1, "text": "Retrieved but not evaluator-gold text."}],
                 }]}]}
        result = MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                               Path.cwd(), anchors={}, candidate_pool_size=1)
        self.assertEqual("retrieved:a1", result["contexts"][0]["evidence"][0]["anchorId"])

    def test_keeps_ocr_degraded_evidence_as_fallback_without_source_identity(self):
        text = "SCOPE SOURCES ETRIEVE EVIDENCI BUILD PLAN COMPOSE CANVAS"
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [{
                     "chunkId": "a1", "sourceVersion": "one:v1", "rank": 1, "page": 3,
                     "retrievalTextSha256": hashlib.sha256(text.encode()).hexdigest(),
                     "evidence": [{"anchorId": "retrieved:a1", "sourceVersion": "one:v1",
                                   "page": 3, "text": text}],
                 }]}]}
        anchors = {
            "route-scope": {"source": "one", "version": "v1", "page": 3, "split": "development",
                            "goldMatch": "SCOPE SOURCES->RETRIEVE EVIDENCE"},
            "route-compose": {"source": "one", "version": "v1", "page": 3, "split": "development",
                              "goldMatch": "BUILD PLAN->COMPOSE CANVAS"},
        }
        evaluator_task = task("a", "one:v1", "PRIVATE-EVALUATOR-ANCHOR")

        result = MODULE.export(trace, [evaluator_task], "development", 1, 0.0, Path.cwd(), anchors=anchors,
                               candidate_pool_size=1)

        self.assertEqual(["retrieved:a1"], [item["anchorId"] for item in result["contexts"][0]["evidence"]])

    def test_reports_when_required_evidence_is_not_model_visible(self):
        text = "Retrieved but not canonical."
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [{
                     "chunkId": "a1", "sourceVersion": "one:v1", "rank": 1, "page": 1,
                     "retrievalTextSha256": hashlib.sha256(text.encode()).hexdigest(),
                     "evidence": [{"anchorId": "retrieved:a1", "sourceVersion": "one:v1", "page": 1,
                                   "text": text}],
                 }]}]}
        evaluator_task = task("a", "one:v1", "missing-anchor")

        result = MODULE.export(trace, [evaluator_task], "development", 1, 0.0, Path.cwd(), anchors={},
                               candidate_pool_size=1)

        self.assertFalse(result["modelVisibleRequiredEvidence"]["ready"])
        self.assertEqual(["missing-anchor"], result["modelVisibleRequiredEvidence"]["tasks"][0]["controlMissing"])

    def test_rejects_retrieved_evidence_with_a_page_other_than_its_candidate(self):
        text = "Retrieved text."
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [{
                     "chunkId": "a1", "sourceVersion": "one:v1", "rank": 1, "page": 1,
                     "retrievalTextSha256": hashlib.sha256(text.encode()).hexdigest(),
                     "evidence": [{"anchorId": "retrieved:a1", "sourceVersion": "one:v1",
                                   "page": 2, "text": text}],
                 }]}]}
        with self.assertRaisesRegex(ValueError, "candidate evidence provenance mismatch"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                          Path.cwd(), anchors={}, candidate_pool_size=1)

    def test_rejects_a_known_anchor_with_text_other_than_its_candidate(self):
        candidate_text = "Retrieved text."
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [{
                     "chunkId": "a1", "sourceVersion": "one:v1", "rank": 1, "page": 1,
                     "retrievalTextSha256": hashlib.sha256(candidate_text.encode()).hexdigest(),
                     "evidence": [{"anchorId": "anchor-a", "sourceVersion": "one:v1",
                                   "page": 1, "text": "Substituted text."}],
                 }]}]}
        anchors = {"anchor-a": {"source": "one", "version": "v1", "page": 1, "split": "development"}}
        with self.assertRaisesRegex(ValueError, "candidate evidence provenance mismatch"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                          Path.cwd(), anchors=anchors, candidate_pool_size=1)

    def test_requires_the_default_frozen_top_40_pool(self):
        candidates = [candidate(f"a{rank}", "one:v1", "anchor-a", rank) for rank in range(1, 40)]
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": candidates}]}
        with self.assertRaisesRegex(ValueError, "candidate pool size is not 40"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 8, 0.0, Path.cwd())

    def test_requires_a_visual_artifact_for_a_declared_multimodal_task(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1)]}]}
        with self.assertRaisesRegex(ValueError, "missing required visual/OCR artifact"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                              Path.cwd(), candidate_pool_size=1, artifact_task_ids={"a"})

    def test_exports_an_explicit_no_retrieval_task_without_fabricated_candidates(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": []}]}
        result = MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                               Path.cwd(), candidate_pool_size=1, no_retrieval_task_ids={"a"})
        self.assertEqual([], result["contexts"][0]["evidence"])
        self.assertFalse(result["taskSummaries"][0]["changed"])

    def test_excludes_no_retrieval_tasks_from_the_contrast_denominator(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [
                     {"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1),
                         candidate("a2", "one:v1", "anchor-a", 2), candidate("a3", "shared:v1", "anchor-a", 3)]},
                     {"taskId": "b", "candidates": []},
                 ]}
        result = MODULE.export(trace, [task("a", "one:v1", "anchor-a"), task("b", "two:v1", "anchor-b")],
                               "development", 2, 0.2, Path.cwd(), candidate_pool_size=3,
                               no_retrieval_task_ids={"b"})
        self.assertEqual(1, result["retrievalRequiredTaskCount"])
        self.assertEqual(1.0, result["changedTaskRate"])

    def test_requires_the_multimodal_artifact_to_match_the_task_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "other.png").write_bytes(b"artifact")
            visual = candidate("a1", "shared:v1", "other", 1)
            visual["evidence"][0].update({"imagePath": "other.png",
                                          "imageSha256": MODULE.sha256(root / "other.png")})
            trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                     "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                     "tasks": [{"taskId": "a", "candidates": [visual]}]}
            with self.assertRaisesRegex(ValueError, "missing required visual/OCR artifact"):
                MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0, root,
                              candidate_pool_size=1, artifact_task_ids={"a"})

    def test_rejects_a_candidate_whose_evidence_claims_another_source(self):
        mismatched = candidate("a1", "one:v1", "anchor-a", 1)
        mismatched["evidence"][0]["sourceVersion"] = "shared:v1"
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [mismatched]}]}
        with self.assertRaisesRegex(ValueError, "candidate/evidence source mismatch"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                          Path.cwd(), candidate_pool_size=1)

    def test_binds_the_cli_trace_to_the_frozen_corpus_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            task_fixture, ground_truth, lock = root / "tasks.json", root / "ground-truth.json", root / "corpus-lock.json"
            task_fixture.write_text("tasks", encoding="utf-8")
            ground_truth.write_text("ground truth", encoding="utf-8")
            lock.write_text(json.dumps({
                "schemaVersion": "material-rag-corpus-lock-v1", "status": "frozen",
                "provenanceFiles": {"fixtures/drawio-generation-tasks-v2.json": MODULE.sha256(task_fixture)},
                "files": {"ground-truth.json": MODULE.sha256(ground_truth)},
            }), encoding="utf-8")
            trace = {"retrievalRun": {"gitCommit": "abc1234", "corpusLockSha256": MODULE.sha256(lock)}}
            MODULE.verify_provenance(trace, lock, task_fixture, ground_truth)
            trace["retrievalRun"]["corpusLockSha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "does not match"):
                MODULE.verify_provenance(trace, lock, task_fixture, ground_truth)


if __name__ == "__main__":
    unittest.main()
