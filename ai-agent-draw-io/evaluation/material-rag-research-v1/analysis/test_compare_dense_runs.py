"""Tests for paired dense-run comparison."""

from __future__ import annotations

import unittest

from compare_dense_runs import compare


def run(mode: str, ranks: list[int]) -> dict:
    cases = [{"caseId": f"case-{index}", "rank": rank, "category": "text",
              "primaryCategory": "exactLookup", "language": "en", "goldAnchorIds": ["a"]}
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


if __name__ == "__main__":
    unittest.main()
