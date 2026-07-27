import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "fixtures" / "build_drawio_generation_tasks_v3.py"
SPEC = importlib.util.spec_from_file_location("build_generation_tasks_v3", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DrawioGenerationTasksV3Test(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(
            (ROOT / "fixtures" / "drawio-generation-tasks-v3.json").read_text(encoding="utf-8")
        )

    def test_committed_fixture_matches_the_deterministic_builder(self):
        self.assertEqual(MODULE.build(), self.fixture)

    def test_has_twenty_isolated_tasks_per_split(self):
        development = [task for task in self.fixture["tasks"] if task["split"] == "development"]
        validation = [task for task in self.fixture["tasks"] if task["split"] == "validation"]

        self.assertEqual(20, len(development))
        self.assertEqual(20, len(validation))
        self.assertEqual(40, len({task["taskId"] for task in self.fixture["tasks"]}))
        self.assertTrue(
            set(self.fixture["developmentChartbookSourceVersions"]).isdisjoint(
                self.fixture["validationChartbookSourceVersions"]
            )
        )

    def test_every_task_has_an_explicit_non_conflicting_source_scope(self):
        no_retrieval = set(self.fixture["developmentNoRetrievalTaskIds"])
        for task in self.fixture["tasks"]:
            mode = task.get("sourceScopeMode")
            self.assertIn(mode, {"selected_only", "chartbook_auto", "none"})
            if mode == "selected_only":
                self.assertEqual(task["sourceVersion"], task.get("selectedMaterialVersion"))
            else:
                self.assertNotIn("selectedMaterialVersion", task)
            if task["taskId"] in no_retrieval:
                self.assertEqual("none", mode)

    def test_every_required_anchor_has_publisher_owned_identity(self):
        identities = {
            item["sourceEvidenceId"]
            for item in json.loads(
                (ROOT / "fixtures" / "generated" / "source-evidence-identities-v1.json")
                .read_text(encoding="utf-8")
            )["identities"]
        }
        required = {
            anchor_id
            for task in self.fixture["tasks"]
            for anchor_id in task["requiredAnchors"]
        }

        self.assertEqual(set(), required - identities)


if __name__ == "__main__":
    unittest.main()
