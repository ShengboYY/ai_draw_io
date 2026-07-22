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
