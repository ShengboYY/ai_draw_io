import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("context", Path(__file__).with_name("select_drawio_context.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ContextSelectionTest(unittest.TestCase):
    def test_preserves_citations_deduplicates_and_prefers_second_source(self):
        candidates = [
            {"chunkId": "a", "sourceVersion": "one", "rank": 1},
            {"chunkId": "a", "sourceVersion": "one", "rank": 2},
            {"chunkId": "b", "sourceVersion": "one", "rank": 3},
            {"chunkId": "c", "sourceVersion": "two", "rank": 4},
        ]
        selected = MODULE.select(candidates, limit=3)
        self.assertEqual(["a", "c", "b"], [value["chunkId"] for value in selected])

    def test_compares_both_arms_and_passes_only_when_context_changes(self):
        raw = {"metrics": {"caseResults": [{
            "caseId": "multi-source",
            "fixedGoldChunkIdsByAnchor": {"gold-a": ["c"]},
            "candidates": [
                {"chunkId": "a", "sourceVersion": "one"},
                {"chunkId": "b", "sourceVersion": "one"},
                {"chunkId": "c", "sourceVersion": "two"},
            ],
        }]}}

        result = MODULE.compare(raw, limit=2, minimum_change_rate=0.2)

        self.assertTrue(result["effectiveExperiment"])
        self.assertEqual(["a", "b"], [value["chunkId"] for value in result["bundles"][0]["controlContext"]])
        self.assertEqual(["a", "c"], [value["chunkId"] for value in result["bundles"][0]["candidateContext"]])
        self.assertEqual(0.0, result["metrics"]["controlMeanGoldEvidenceRecall"])
        self.assertEqual(1.0, result["metrics"]["candidateMeanGoldEvidenceRecall"])
        self.assertEqual([1.0, 1.0], result["metrics"]["pairedGoldEvidenceRecallDelta95CI"])

    def test_marks_single_source_no_op_as_ineffective(self):
        raw = {"metrics": {"caseResults": [{
            "caseId": "single-source",
            "fixedGoldChunkIdsByAnchor": {},
            "candidates": [
                {"chunkId": "a", "sourceVersion": "one"},
                {"chunkId": "b", "sourceVersion": "one"},
            ],
        }]}}

        result = MODULE.compare(raw, limit=2)

        self.assertFalse(result["effectiveExperiment"])
        self.assertEqual(0, result["changedCaseCount"])
