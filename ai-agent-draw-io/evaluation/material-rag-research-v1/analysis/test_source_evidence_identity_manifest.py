"""Contract checks for source-owned draw.io evidence identities."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "fixtures/generated/source-evidence-identities-v1.json"


class SourceEvidenceIdentityManifestTest(unittest.TestCase):
    def test_active_drawio_identities_are_source_owned_and_answer_free(self) -> None:
        """The hydration producer needs source identity without loading evaluator task/answer fields."""
        payload = json.loads(MANIFEST.read_text(encoding="utf-8"))
        identities = payload["identities"]

        self.assertEqual("material-rag-source-evidence-identities-v1", payload["schemaVersion"])
        self.assertTrue({"daa-route-scope", "daa-route-compose", "dwh-canonical", "dpw-budgets"}
                        .issubset({identity["sourceEvidenceId"] for identity in identities}))
        for identity in identities:
            self.assertEqual({"sourceEvidenceId", "sourceVersion", "page", "match"}, set(identity))
            self.assertIn(identity["match"]["kind"], {"exact_text", "visual_page"})
            self.assertFalse({"queries", "expectedAnswer", "requiredAnchors", "goldMatch"}
                             .intersection(identity))


if __name__ == "__main__":
    unittest.main()
