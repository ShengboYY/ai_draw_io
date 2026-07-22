"""Tests for paired dense-run comparison."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from compare_dense_runs import attach_corpus_lock_snapshot, compare


def run(mode: str, ranks: list[int]) -> dict:
    cases = [{"caseId": f"case-{index}", "rank": rank, "category": "text",
              "primaryCategory": "exactLookup", "language": "en", "goldAnchorIds": ["a"],
              "mappable": True, "candidates": [{"rank": 1, "vectorId": "v",
              "sourceVersion": "source:v1", "chunkId": "chunk"}]}
             for index, rank in enumerate(ranks)]
    return {
        "gitCommit": "abc", "corpusLockSha256": "lock", "split": "validation",
        "embeddingModel": "model", "tokenizerFingerprint": "tokenizer", "candidateLimit": 40,
        "canonicalMode": mode, "chunkCount": 10, "metrics": {"caseResults": cases},
    }


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
