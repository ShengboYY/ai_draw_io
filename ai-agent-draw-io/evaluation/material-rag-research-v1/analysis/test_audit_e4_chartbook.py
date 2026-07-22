"""Tests for the E4 multi-source chartbook fixture contract."""

from __future__ import annotations

import json
import shutil
import tempfile
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

    def test_case_scope_drift_from_chartbook_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary_root = Path(directory)
            shutil.copytree(ROOT / "fixtures", temporary_root / "fixtures")
            payload_path = temporary_root / "fixtures/generated/e4-chartbook-cases.json"
            payload = json.loads(payload_path.read_text(encoding="utf-8"))
            payload["cases"][0]["unmountedSourceVersions"] = []
            payload_path.write_text(json.dumps(payload), encoding="utf-8")

            result = audit_chartbook_cases(temporary_root)

        self.assertEqual("not_ready", result["status"])
        self.assertFalse(result["checks"]["caseScopeMatchesChartbook"])


if __name__ == "__main__":
    unittest.main()
