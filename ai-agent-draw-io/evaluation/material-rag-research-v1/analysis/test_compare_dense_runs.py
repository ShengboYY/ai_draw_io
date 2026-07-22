"""Tests for paired dense-run comparison."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from compare_dense_runs import attach_corpus_lock_snapshot, compare


def run(mode: str, ranks: list[int]) -> dict:
    cases = [{"caseId": f"case-{index}", "rank": rank, "category": "text",
              "primaryCategory": "exactLookup", "language": "en", "goldAnchorIds": ["a"],
              "fixedGoldChunkIdsByAnchor": {"a": ["chunk"]},
              "mountedSourceVersions": ["source:v1"],
              "unmountedSourceVersions": [],
              "goldSourceVersions": ["source:v1"],
              "mappable": True, "candidates": [{"rank": 1, "vectorId": "v",
              "sourceVersion": "source:v1", "chunkId": "chunk"}],
              "retrievalPoolCandidates": [{"rank": 1, "vectorId": "v",
              "sourceVersion": "source:v1", "chunkId": "chunk"}],
              "denseCandidates": [{"rank": 1, "vectorId": "v",
              "sourceVersion": "source:v1", "chunkId": "chunk"}],
              "lexicalCandidates": [{"rank": 1, "vectorId": "v",
              "sourceVersion": "source:v1", "chunkId": "chunk"}]}
             for index, rank in enumerate(ranks)]
    return {
        "gitCommit": "abc", "corpusLockSha256": "lock", "split": "validation",
        "embeddingModel": "model", "tokenizerFingerprint": "tokenizer", "candidateLimit": 40,
        "canonicalMode": mode, "chunkMode": "flat-leaf-v1", "retrievalMode": "dense-v1",
        "queryMode": "original-v1", "queryRewriteFingerprint": "rewrite",
        "postprocessMode": "ranked-raw-v1", "dedupFingerprint": "dedup",
        "retrievalPoolLimit": 80,
        "lexicalRankerFingerprint": "lexical", "fusionFingerprint": "rrf", "chunkCount": 10,
        "metrics": {"caseResults": cases},
    }


def candidates(source_versions: list[str]) -> list[dict]:
    return [
        {"rank": rank, "vectorId": f"v-{rank}", "sourceVersion": source,
         "chunkId": f"chunk-{rank}"}
        for rank, source in enumerate(source_versions, start=1)
    ]


class CompareDenseRunsTest(unittest.TestCase):

    def test_paired_improvement_is_computed_from_case_ranks(self) -> None:
        result = compare(run("e0-v4", [0, 20, 1]), run("e1-v5", [5, 10, 1]))

        self.assertEqual(3, result["caseCount"])
        self.assertAlmostEqual(2 / 3, result["aggregates"]["recallAt10"]["delta"])
        self.assertEqual("comparable", result["status"])

    def test_control_drift_is_rejected(self) -> None:
        e0 = run("e0-v4", [1])
        e1 = run("e1-v5", [1])
        e1["split"] = "development"

        with self.assertRaisesRegex(ValueError, "controls differ"):
            compare(e0, e1)

    def test_chunk_experiment_keeps_canonical_mode_fixed(self) -> None:
        flat = run("e1-v5", [5, 1])
        parent = run("e1-v5", [1, 1])
        parent["chunkMode"] = "parent-context-500-v1"

        result = compare(flat, parent, "chunkMode")

        self.assertEqual("chunkMode", result["experimentVariable"]["field"])
        self.assertEqual("flat-leaf-v1", result["experimentVariable"]["baseline"])
        self.assertEqual("parent-context-500-v1", result["experimentVariable"]["candidate"])

    def test_chunk_experiment_rejects_canonical_drift(self) -> None:
        flat = run("e1-v5", [1])
        parent = run("e0-v4", [1])
        parent["chunkMode"] = "parent-context-500-v1"

        with self.assertRaisesRegex(ValueError, "canonicalMode"):
            compare(flat, parent, "chunkMode")

    def test_chunk_experiment_rejects_gold_to_child_drift(self) -> None:
        flat = run("e1-v5", [1])
        parent = run("e1-v5", [1])
        parent["chunkMode"] = "parent-context-500-v1"
        parent["metrics"]["caseResults"][0]["fixedGoldChunkIdsByAnchor"]["a"] = ["neighbor"]

        with self.assertRaisesRegex(ValueError, "fixedGoldChunkIdsByAnchor"):
            compare(flat, parent, "chunkMode")

    def test_retrieval_experiment_keeps_dense_lane_fixed(self) -> None:
        dense = run("e1-v5", [5, 1])
        hybrid = run("e1-v5", [1, 1])
        hybrid["retrievalMode"] = "hybrid-projection-rrf-v1"

        result = compare(dense, hybrid, "retrievalMode")

        self.assertEqual("retrievalMode", result["experimentVariable"]["field"])
        self.assertEqual("dense-v1", result["experimentVariable"]["baseline"])
        self.assertEqual("hybrid-projection-rrf-v1", result["experimentVariable"]["candidate"])

    def test_retrieval_experiment_rejects_dense_lane_drift(self) -> None:
        dense = run("e1-v5", [1])
        hybrid = run("e1-v5", [1])
        hybrid["retrievalMode"] = "hybrid-projection-rrf-v1"
        hybrid["metrics"]["caseResults"][0]["denseCandidates"][0]["chunkId"] = "other"

        with self.assertRaisesRegex(ValueError, "denseCandidates"):
            compare(dense, hybrid, "retrievalMode")

    def test_retrieval_experiment_rejects_lexical_lane_drift(self) -> None:
        dense = run("e1-v5", [1])
        hybrid = run("e1-v5", [1])
        hybrid["retrievalMode"] = "hybrid-projection-rrf-v1"
        hybrid["metrics"]["caseResults"][0]["lexicalCandidates"][0]["chunkId"] = "other"

        with self.assertRaisesRegex(ValueError, "lexicalCandidates"):
            compare(dense, hybrid, "retrievalMode")

    def test_query_experiment_keeps_retrieval_mode_fixed(self) -> None:
        original = run("e1-v5", [5, 1])
        rewritten = run("e1-v5", [1, 1])
        rewritten["queryMode"] = "evidence-focused-v1"

        result = compare(original, rewritten, "queryMode")

        self.assertEqual("queryMode", result["experimentVariable"]["field"])
        self.assertEqual("original-v1", result["experimentVariable"]["baseline"])
        self.assertEqual("evidence-focused-v1", result["experimentVariable"]["candidate"])

    def test_query_experiment_rejects_retrieval_mode_drift(self) -> None:
        original = run("e1-v5", [1])
        rewritten = run("e1-v5", [1])
        rewritten["queryMode"] = "evidence-focused-v1"
        rewritten["retrievalMode"] = "hybrid-projection-rrf-v1"

        with self.assertRaisesRegex(ValueError, "retrievalMode"):
            compare(original, rewritten, "queryMode")

    def test_postprocess_experiment_keeps_query_and_retrieval_fixed(self) -> None:
        raw = run("e1-v5", [5, 1])
        deduplicated = run("e1-v5", [1, 1])
        deduplicated["postprocessMode"] = "evidence-dedup-v1"

        result = compare(raw, deduplicated, "postprocessMode")

        self.assertEqual("postprocessMode", result["experimentVariable"]["field"])
        self.assertEqual("ranked-raw-v1", result["experimentVariable"]["baseline"])
        self.assertEqual("evidence-dedup-v1", result["experimentVariable"]["candidate"])

    def test_postprocess_experiment_reports_candidate_activation(self) -> None:
        raw = run("e1-v5", [1])
        deduplicated = run("e1-v5", [1])
        deduplicated["postprocessMode"] = "evidence-dedup-v1"
        raw["metrics"]["caseResults"][0]["candidates"] = [
            {"rank": rank, "vectorId": f"v-{chunk}", "sourceVersion": "source:v1",
             "chunkId": chunk}
            for rank, chunk in enumerate(("a", "b", "c"), start=1)
        ]
        deduplicated["metrics"]["caseResults"][0]["candidates"] = [
            {"rank": rank, "vectorId": f"v-{chunk}", "sourceVersion": "source:v1",
             "chunkId": chunk}
            for rank, chunk in enumerate(("a", "c", "d"), start=1)
        ]

        result = compare(raw, deduplicated, "postprocessMode")

        self.assertEqual(1, result["postprocessActivation"]["changedCases"])
        self.assertEqual(1, result["postprocessActivation"]["removedBaselinePositions"])
        self.assertAlmostEqual(1 / 3, result["postprocessActivation"]["replacementRate"])

    def test_postprocess_experiment_rejects_query_drift(self) -> None:
        raw = run("e1-v5", [1])
        deduplicated = run("e1-v5", [1])
        deduplicated["postprocessMode"] = "evidence-dedup-v1"
        deduplicated["queryMode"] = "evidence-focused-v1"

        with self.assertRaisesRegex(ValueError, "queryMode"):
            compare(raw, deduplicated, "postprocessMode")

    def test_postprocess_experiment_rejects_retrieval_pool_drift(self) -> None:
        raw = run("e1-v5", [1])
        deduplicated = run("e1-v5", [1])
        deduplicated["postprocessMode"] = "evidence-dedup-v1"
        deduplicated["metrics"]["caseResults"][0]["retrievalPoolCandidates"][0][
            "chunkId"] = "other"

        with self.assertRaisesRegex(ValueError, "retrievalPoolCandidates drift"):
            compare(raw, deduplicated, "postprocessMode")

    def test_postprocess_experiment_reports_chartbook_source_diversity(self) -> None:
        raw = run("e1-v5", [1])
        diversified = run("e1-v5", [1])
        diversified["postprocessMode"] = "source-diversity-v1"
        for value in (raw, diversified):
            case = value["metrics"]["caseResults"][0]
            case["mountedSourceVersions"] = ["source-a:v1", "source-b:v1"]
            case["goldSourceVersions"] = ["source-a:v1", "source-b:v1"]
            case["retrievalPoolCandidates"] = candidates(
                ["source-a:v1"] * 10 + ["source-b:v1"])
        raw["metrics"]["caseResults"][0]["candidates"] = candidates(["source-a:v1"] * 10)
        diversified["metrics"]["caseResults"][0]["candidates"] = candidates(
            ["source-a:v1"] * 4 + ["source-b:v1"] * 6)

        result = compare(raw, diversified, "postprocessMode")

        self.assertAlmostEqual(0.5, result["sourceDiversity"]["e0"]["meanMountedCoverageAt10"])
        self.assertAlmostEqual(1.0, result["sourceDiversity"]["e1"]["meanMountedCoverageAt10"])
        self.assertAlmostEqual(0.0, result["sourceDiversity"]["e0"]["goldSourceRecallAt10"])
        self.assertAlmostEqual(1.0, result["sourceDiversity"]["e1"]["goldSourceRecallAt10"])
        self.assertEqual(0, result["sourceDiversity"]["e1"]["unmountedLeakagePositions"])

    def test_missing_raw_candidate_sequence_is_rejected(self) -> None:
        e0 = run("e0-v4", [1])
        e1 = run("e1-v5", [1])
        del e1["metrics"]["caseResults"][0]["candidates"]

        with self.assertRaisesRegex(ValueError, "raw candidates"):
            compare(e0, e1)

    def test_wrong_corpus_lock_snapshot_is_rejected(self) -> None:
        result = compare(run("e0-v4", [1]), run("e1-v5", [1]))
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / "corpus-lock.json"
            snapshot.write_text("wrong lock", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "snapshot hash differs"):
                attach_corpus_lock_snapshot(result, snapshot)


if __name__ == "__main__":
    unittest.main()
