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
            "request": f"Use {anchor} evidence.",
            "citationAssertions": {"mustCiteAnchors": [anchor]}}


def candidate(chunk_id: str, source: str, anchor: str, rank: int) -> dict:
    text = f"Evidence {anchor}."
    return {"chunkId": chunk_id, "sourceVersion": source, "rank": rank,
            "page": 1, "retrievalTextSha256": hashlib.sha256(text.encode()).hexdigest(),
            "evidence": [{"anchorId": anchor, "sourceVersion": source, "page": 1, "text": text}]}


class PairedHydrationExportTest(unittest.TestCase):
    def test_accepts_rank_lineage_that_reproduces_exported_candidates(self):
        trace = {
            "retrievalRun": {
                "queryRankLineageFingerprint": "original-rewrite-top80-fused-ranks-v1",
            },
            "tasks": [{
                "taskId": "task-1",
                "candidates": [{"chunkId": "shared"}, {"chunkId": "rewritten-only"}],
                "queryRankLineage": {
                    "originalTop80ChunkIds": ["shared"],
                    "rewrittenTop80ChunkIds": ["rewritten-only", "shared"],
                    "fusedTop40": [
                        {"rank": 1, "chunkId": "shared", "originalRank": 1, "rewrittenRank": 2},
                        {"rank": 2, "chunkId": "rewritten-only", "rewrittenRank": 1},
                    ],
                },
            }],
        }

        MODULE.verify_rank_lineage(trace)

    def test_rejects_rank_lineage_that_does_not_match_exported_candidates(self):
        trace = {
            "retrievalRun": {
                "queryRankLineageFingerprint": "original-rewrite-top80-fused-ranks-v1",
            },
            "tasks": [{
                "taskId": "task-1",
                "candidates": [{"chunkId": "fused-a"}, {"chunkId": "fused-b"}],
                "queryRankLineage": {
                    "originalTop80ChunkIds": ["fused-a"],
                    "rewrittenTop80ChunkIds": ["fused-b"],
                    "fusedTop40": [
                        {"rank": 1, "chunkId": "fused-b", "rewrittenRank": 1},
                        {"rank": 2, "chunkId": "fused-a", "originalRank": 1},
                    ],
                },
            }],
        }

        with self.assertRaisesRegex(ValueError, "fused candidate order"):
            MODULE.verify_rank_lineage(trace)

    def test_publisher_identity_selector_reserves_request_relevant_identity(self):
        candidates = [
            candidate(f"a{rank}", "one:v1", f"noise-{rank}", rank)
            for rank in range(1, 10)
        ]
        candidates[8]["evidence"][0]["anchorId"] = "dwh-auto-scope"

        selected = MODULE.select_with_publisher_identity_relevance(
            candidates, "Contrast automatic wider search with the selected scope.", 8
        )

        # "automatic" matches the publisher's "auto" token by the frozen prefix rule.
        self.assertEqual([1, 2, 3, 4, 5, 6, 7, 9], [item["rank"] for item in selected])

    def test_publisher_identity_selector_ignores_fallback_retrieved_ids(self):
        candidates = [
            candidate(f"a{rank}", "one:v1", f"noise-{rank}", rank)
            for rank in range(1, 10)
        ]
        candidates[8]["evidence"][0]["anchorId"] = "retrieved:explicit-policy"

        selected = MODULE.select_with_publisher_identity_relevance(
            candidates, "Use the explicit policy.", 8
        )

        self.assertEqual(list(range(1, 9)), [item["rank"] for item in selected])

    def test_artifact_selector_reserves_top_distinct_images_without_using_gold(self):
        candidates = [
            candidate(f"a{rank}", f"source-{rank}:v1", f"anchor-{rank}", rank)
            for rank in range(1, 9)
        ]
        candidates.extend([
            candidate("a9", "source-1:v1", "anchor-9", 9),
            candidate("a10", "source-1:v1", "anchor-10", 10),
        ])
        for index, image_candidate in enumerate(
                (candidates[1], candidates[5], candidates[8], candidates[9]), start=1):
            image_candidate["evidence"][0].update({
                "imagePath": f"image-{index if index < 4 else 3}.png",
                "imageSha256": f"hash-{index if index < 4 else 3}",
            })

        selected = MODULE.select_with_artifact_coverage(candidates, 8)

        # The third distinct visual page is rank 9; the rank-10 duplicate must not consume a slot.
        self.assertEqual([1, 2, 3, 4, 5, 6, 7, 9], [item["rank"] for item in selected])

    def test_artifact_selector_reserves_the_first_visual_from_another_source(self):
        candidates = [
            candidate(f"a{rank}", f"source-{rank}:v1", f"anchor-{rank}", rank)
            for rank in range(1, 8)
        ]
        candidates.extend([
            candidate(f"a{rank}", "source-1:v1" if rank < 12 else "source-2:v1",
                      f"anchor-{rank}", rank)
            for rank in range(8, 13)
        ])
        for index in range(7, 12):
            candidates[index]["evidence"][0].update({
                "imagePath": f"image-{index + 1}.png",
                "imageSha256": f"hash-{index + 1}",
            })

        selected = MODULE.select_with_artifact_coverage(candidates, 8)

        # Source two's first visual survives even though four source-one images rank above it.
        self.assertIn(12, [item["rank"] for item in selected])

    def test_source_scope_distinguishes_selected_material_from_automatic_chartbook(self):
        selected = {
            "taskId": "selected",
            "sourceVersion": "one:v1",
            "selectedMaterialVersion": "one:v1",
            "sourceScopeMode": "selected_only",
        }
        automatic = {
            "taskId": "automatic",
            "sourceVersion": "one:v1",
            "sourceScopeMode": "chartbook_auto",
        }

        self.assertEqual({"one:v1"}, MODULE.allowed_sources(selected, {"one:v1", "two:v1"}))
        self.assertEqual({"one:v1", "two:v1"},
                         MODULE.allowed_sources(automatic, {"one:v1", "two:v1"}))

    def test_exports_one_control_and_candidate_context_per_task(self):
        tasks = [task("a", "one:v1", "shared-policy"), task("b", "two:v1", "shared-rule")]
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [
                     {"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1),
                         candidate("a2", "one:v1", "anchor-a", 2),
                         candidate("a3", "shared:v1", "shared-policy", 3)]},
                     {"taskId": "b", "candidates": [candidate("b1", "two:v1", "anchor-b", 1),
                         candidate("b2", "two:v1", "anchor-b", 2),
                         candidate("b3", "shared:v1", "shared-rule", 3)]},
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

    def test_candidate_readiness_allows_control_missing_evidence_for_end_to_end_comparison(self):
        evaluator_task = task("a", "one:v1", "anchor-a")
        contexts = [
            {"taskId": "a", "arm": "control", "evidence": []},
            {"taskId": "a", "arm": "candidate", "evidence": [{
                "anchorId": "anchor-a", "sourceVersion": "one:v1", "page": 1, "text": "Evidence.",
            }]},
        ]

        readiness = MODULE.required_evidence_readiness([evaluator_task], contexts, set())

        self.assertTrue(readiness["ready"])
        self.assertFalse(readiness["controlReady"])
        self.assertTrue(readiness["candidateReady"])

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

    def test_accepts_exact_smaller_pool_for_selected_only_source(self):
        selected_task = {
            **task("a", "one:v1", "anchor-a"),
            "sourceScopeMode": "selected_only",
            "selectedMaterialVersion": "one:v1",
        }
        candidates = [candidate(f"a{rank}", "one:v1", "anchor-a", rank) for rank in range(1, 4)]
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {
                     "runId": "r", "gitCommit": "c", "corpusLockSha256": "l",
                     "sourceIndexedVectorCounts": {"one:v1": 3, "shared:v1": 7},
                 },
                 "tasks": [{"taskId": "a", "candidates": candidates}]}

        result = MODULE.export(
            trace, [selected_task], "development", 2, 0.0, Path.cwd(),
            chartbook_sources={"one:v1", "shared:v1"}, candidate_pool_size=40,
        )

        self.assertEqual(3, result["taskSummaries"][0]["scopedCandidateCount"])

    def test_rejects_empty_pool_when_allowed_sources_have_projected_chunks(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {
                     "runId": "r", "gitCommit": "c", "corpusLockSha256": "l",
                     "sourceIndexedVectorCounts": {"one:v1": 50, "shared:v1": 20},
                 },
                 "tasks": [{"taskId": "a", "candidates": []}]}

        with self.assertRaisesRegex(ValueError, "candidate pool size is not 40"):
            MODULE.export(
                trace, [task("a", "one:v1", "anchor-a")], "development", 8, 0.0,
                Path.cwd(), chartbook_sources={"one:v1", "shared:v1"},
            )

    def test_requires_a_visual_artifact_for_a_declared_multimodal_task(self):
        trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                 "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                 "tasks": [{"taskId": "a", "candidates": [candidate("a1", "one:v1", "anchor-a", 1)]}]}
        with self.assertRaisesRegex(ValueError, "missing required visual/OCR artifact"):
            MODULE.export(trace, [task("a", "one:v1", "anchor-a")], "development", 1, 0.0,
                              Path.cwd(), candidate_pool_size=1, artifact_task_ids={"a"})

    def test_allows_control_to_miss_artifact_when_candidate_has_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "visual.png").write_bytes(b"visual")
            candidates = [
                candidate("a1", "one:v1", "anchor-a", 1),
                candidate("a2", "one:v1", "anchor-a", 2),
                candidate("a3", "one:v1", "visual-policy", 3),
            ]
            candidates[2]["evidence"][0].update({
                "imagePath": "visual.png",
                "imageSha256": MODULE.sha256(root / "visual.png"),
            })
            trace = {"schemaVersion": "material-rag-drawio-task-hydration-candidates-v1",
                     "retrievalRun": {"runId": "r", "gitCommit": "c", "corpusLockSha256": "l"},
                     "tasks": [{"taskId": "a", "candidates": candidates}]}

            result = MODULE.export(
                trace, [task("a", "one:v1", "visual-policy")], "development", 2, 0.2, root,
                candidate_pool_size=3, artifact_task_ids={"a"},
            )

            self.assertFalse(any("imagePath" in item for item in result["contexts"][0]["evidence"]))
            self.assertTrue(any("imagePath" in item for item in result["contexts"][1]["evidence"]))

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
                         candidate("a2", "one:v1", "anchor-a", 2),
                         candidate("a3", "shared:v1", "shared-policy", 3)]},
                     {"taskId": "b", "candidates": []},
                 ]}
        result = MODULE.export(trace, [task("a", "one:v1", "shared-policy"),
                                      task("b", "two:v1", "anchor-b")],
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
            identities = root / "source-evidence-identities.json"
            task_fixture.write_text("tasks", encoding="utf-8")
            ground_truth.write_text("ground truth", encoding="utf-8")
            identities.write_text(json.dumps({
                "schemaVersion": "material-rag-source-evidence-identities-v1", "identities": [],
            }), encoding="utf-8")
            lock.write_text(json.dumps({
                "schemaVersion": "material-rag-corpus-lock-v1", "status": "frozen",
                "provenanceFiles": {"fixtures/tasks.json": MODULE.sha256(task_fixture)},
                "files": {"ground-truth.json": MODULE.sha256(ground_truth)},
            }), encoding="utf-8")
            trace = {"retrievalRun": {
                "gitCommit": "abc1234", "corpusLockSha256": MODULE.sha256(lock),
                "candidateQueryMode": "original-evidence-rrf-v1",
                "queryRewriteFingerprint":
                    "drawio-bilingual-evidence-focused-v2:han-aware-prefix:frozen-domain-terms",
                "queryFusionFingerprint": "equal-rrf-v1:k60:original1.0:rewritten1.0",
            }}
            MODULE.verify_provenance(trace, lock, task_fixture, ground_truth, identities)
            trace["retrievalRun"]["candidateQueryMode"] = "original-v1"
            with self.assertRaisesRegex(ValueError, "candidate query lane"):
                MODULE.verify_provenance(trace, lock, task_fixture, ground_truth, identities)
            trace["retrievalRun"]["candidateQueryMode"] = "original-evidence-rrf-v1"
            trace["retrievalRun"]["queryFusionFingerprint"] = "unknown"
            with self.assertRaisesRegex(ValueError, "candidate query lane"):
                MODULE.verify_provenance(trace, lock, task_fixture, ground_truth, identities)
            trace["retrievalRun"]["queryFusionFingerprint"] = (
                "equal-rrf-v1:k60:original1.0:rewritten1.0"
            )
            trace["retrievalRun"]["corpusLockSha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "does not match"):
                MODULE.verify_provenance(trace, lock, task_fixture, ground_truth, identities)


if __name__ == "__main__":
    unittest.main()
