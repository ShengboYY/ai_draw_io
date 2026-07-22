import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("build_drawio_generation_prompts.py")
SPEC = importlib.util.spec_from_file_location("generation_prompts", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DrawioGenerationPromptTest(unittest.TestCase):
    def test_builds_locatable_evidence_prompt_without_xml_gold_leakage(self):
        task = {
            "taskId": "task-1",
            "split": "development",
            "sourceVersion": "architecture:v1",
            "request": "Create an editable evidence route.",
            "xmlAssertions": {"requiredLabels": ["PRIVATE EXPECTED LABEL"]},
        }
        context = {
            "taskId": "task-1",
            "arm": "candidate",
            "evidence": [{
                "anchorId": "route-a",
                "sourceVersion": "architecture:v1",
                "page": 3,
                "text": "SCOPE SOURCES is followed by RETRIEVE EVIDENCE.",
            }],
        }

        prompt = MODULE.build_prompt(task, context)

        self.assertIn("Create an editable evidence route.", prompt)
        self.assertIn("route-a | architecture:v1 | page 3", prompt)
        self.assertIn("SCOPE SOURCES is followed by RETRIEVE EVIDENCE.", prompt)
        self.assertNotIn("PRIVATE EXPECTED LABEL", prompt)

    def test_builds_only_the_requested_split_and_arm(self):
        tasks = [
            {"taskId": "dev", "split": "development", "sourceVersion": "source:v1", "request": "Dev", "xmlAssertions": {}},
            {"taskId": "val", "split": "validation", "sourceVersion": "source:v1", "request": "Val", "xmlAssertions": {}},
        ]
        contexts = [
            {"taskId": "dev", "arm": "control", "evidence": []},
            {"taskId": "dev", "arm": "candidate", "evidence": []},
            {"taskId": "val", "arm": "candidate", "evidence": []},
        ]

        bundles = MODULE.build_bundles(tasks, contexts, split="development", arm="candidate")

        self.assertEqual(["dev"], [bundle["taskId"] for bundle in bundles])
        self.assertEqual("candidate", bundles[0]["arm"])

    def test_rejects_duplicate_context_for_the_same_task_and_arm(self):
        task = {"taskId": "dev", "split": "development", "sourceVersion": "source:v1", "request": "Dev"}
        contexts = [
            {"taskId": "dev", "arm": "candidate", "evidence": []},
            {"taskId": "dev", "arm": "candidate", "evidence": []},
        ]

        with self.assertRaisesRegex(ValueError, "duplicate candidate context"):
            MODULE.build_bundles([task], contexts, split="development", arm="candidate")


if __name__ == "__main__":
    unittest.main()
