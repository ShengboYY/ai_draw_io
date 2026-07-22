"""Tests for the E4 multi-source chartbook fixture contract."""

from __future__ import annotations

import unittest
from pathlib import Path

from audit_e4_chartbook import audit_chartbook_cases


ROOT = Path(__file__).resolve().parents[1]


class AuditE4ChartbookTest(unittest.TestCase):

    def test_current_fixture_is_multi_source_and_split_safe(self) -> None:
        result = audit_chartbook_cases(ROOT)

        self.assertEqual("ready", result["status"])
        self.assertEqual({"development": 26, "validation": 20}, result["caseCounts"])
        self.assertEqual(0, result["invalidCaseCount"])
        self.assertTrue(result["checks"]["everyCaseHasMultipleGoldSources"])
        self.assertTrue(result["checks"]["mountedAndUnmountedAreDisjoint"])
        self.assertTrue(result["checks"]["allGoldSourcesAreMounted"])
        self.assertTrue(result["checks"]["allSourcesStayWithinSplit"])


if __name__ == "__main__":
    unittest.main()
