import importlib.util
import hashlib
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("build_drawio_generation_prompts.py")
SPEC = importlib.util.spec_from_file_location("generation_prompts", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
READY_HYDRATION_ARTIFACT = {"path": "results/ready-hydration.json", "sha256": "a" * 64}


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
        self.assertIn("CIT-001 | architecture:v1 | page 3", prompt)
        self.assertIn("SCOPE SOURCES is followed by RETRIEVE EVIDENCE.", prompt)
        self.assertIn("Allowed citations", prompt)
        self.assertIn("citationId=CIT-001; sourceVersion=architecture:v1; page=3", prompt)
        self.assertIn("must not use draw.io mxCell IDs", prompt)
        self.assertNotIn("route-a", prompt)
        self.assertNotIn("PRIVATE EXPECTED LABEL", prompt)

    def test_includes_existing_xml_but_keeps_edit_assertions_private(self):
        task = {
            "taskId": "edit",
            "request": "Change only the fact node.",
            "inputXml": "<mxGraphModel><root><mxCell id='fact' value='old'/></root></mxGraphModel>",
            "editAssertions": {"requiredCellValues": {"fact": "PRIVATE NEW VALUE"}},
        }

        prompt = MODULE.build_prompt(task, {"evidence": []})

        self.assertIn("Existing editable XML to modify", prompt)
        self.assertIn("id='fact'", prompt)
        self.assertNotIn("PRIVATE NEW VALUE", prompt)

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

        bundles = MODULE.build_bundles(tasks, contexts, split="development", arm="candidate",
                                       hydration_artifact=READY_HYDRATION_ARTIFACT)

        self.assertEqual(["dev"], [bundle["taskId"] for bundle in bundles])
        self.assertEqual("candidate", bundles[0]["arm"])
        self.assertTrue(bundles[0]["modelVisibleRequiredEvidenceReady"])
        self.assertEqual(READY_HYDRATION_ARTIFACT, bundles[0]["hydrationArtifact"])

    def test_rejects_bundle_freeze_when_model_visible_evidence_is_not_ready(self):
        tasks = [{"taskId": "dev", "split": "development", "sourceVersion": "source:v1", "request": "Dev"}]
        contexts = [{"taskId": "dev", "arm": "candidate", "evidence": []}]

        with self.assertRaisesRegex(ValueError, "required evidence readiness gate"):
            MODULE.build_bundles(tasks, contexts, split="development", arm="candidate",
                                 hydration_artifact=None)

    def test_rejects_duplicate_context_for_the_same_task_and_arm(self):
        task = {"taskId": "dev", "split": "development", "sourceVersion": "source:v1", "request": "Dev"}
        contexts = [
            {"taskId": "dev", "arm": "candidate", "evidence": []},
            {"taskId": "dev", "arm": "candidate", "evidence": []},
        ]

        with self.assertRaisesRegex(ValueError, "duplicate candidate context"):
            MODULE.build_bundles([task], contexts, split="development", arm="candidate",
                                 hydration_artifact=READY_HYDRATION_ARTIFACT)

    def test_rejects_a_context_that_expands_the_frozen_chartbook_scope(self):
        task = {"taskId": "dev", "split": "development", "sourceVersion": "source:v1", "request": "Dev"}
        contexts = [{"taskId": "dev", "arm": "candidate", "allowedSourceVersions": ["source:v1", "other:v1"],
                     "evidence": []}]

        with self.assertRaisesRegex(ValueError, "context scope mismatch"):
            MODULE.build_bundles([task], contexts, split="development", arm="candidate",
                                 chartbook_sources={"source:v1"},
                                 hydration_artifact=READY_HYDRATION_ARTIFACT)

    def test_selected_only_bundle_does_not_inherit_other_chartbook_sources(self):
        task = {
            "taskId": "dev", "split": "development", "sourceVersion": "source:v1",
            "selectedMaterialVersion": "source:v1", "sourceScopeMode": "selected_only",
            "request": "Dev",
        }
        contexts = [{
            "taskId": "dev", "arm": "candidate",
            "allowedSourceVersions": ["source:v1"], "evidence": [],
        }]

        bundles = MODULE.build_bundles(
            [task], contexts, split="development", arm="candidate",
            chartbook_sources={"source:v1", "other:v1"},
            hydration_artifact=READY_HYDRATION_ARTIFACT,
        )

        self.assertEqual(["dev"], [bundle["taskId"] for bundle in bundles])

    def test_validation_bundle_uses_its_frozen_chartbook_scope(self):
        task = {
            "taskId": "val", "split": "validation", "sourceVersion": "val-source:v1",
            "sourceScopeMode": "chartbook_auto", "request": "Validation",
        }
        contexts = [{
            "taskId": "val", "arm": "candidate",
            "allowedSourceVersions": ["other-val:v1", "val-source:v1"], "evidence": [],
        }]

        bundles = MODULE.build_bundles(
            [task], contexts, split="validation", arm="candidate",
            chartbook_sources={"val-source:v1", "other-val:v1"},
            hydration_artifact=READY_HYDRATION_ARTIFACT,
        )

        self.assertEqual(["val"], [bundle["taskId"] for bundle in bundles])

    def test_preserves_attached_visual_artifact_paths(self):
        task = {"taskId": "visual", "split": "development", "sourceVersion": "source:v1", "request": "Inspect route"}
        contexts = [{"taskId": "visual", "arm": "fixed", "evidence": [{
            "anchorId": "route-a", "sourceVersion": "source:v1", "page": 3,
            "text": "Inspect the attached route image.", "imagePath": "fixtures/generated/images/route.png",
        }]}]

        bundle = MODULE.build_bundles([task], contexts, split="development", arm="fixed",
                                      hydration_artifact=READY_HYDRATION_ARTIFACT)[0]

        self.assertIn("Attached visual artifact", bundle["prompt"])
        self.assertEqual(["fixtures/generated/images/route.png"], bundle["imagePaths"])
        self.assertEqual(hashlib.sha256(bundle["prompt"].encode()).hexdigest(), bundle["promptSha256"])

    def test_freezes_only_visible_evidence_as_exact_citation_options(self):
        task = {
            "taskId": "citations", "split": "development", "sourceVersion": "source:v1",
            "request": "Create an evidence-backed route.",
            "citationAssertions": {"mustCiteAnchors": ["PRIVATE-EVALUATOR-ANCHOR"]},
        }
        contexts = [{"taskId": "citations", "arm": "candidate", "evidence": [{
            "anchorId": "visible-a", "sourceVersion": "source:v1", "page": 2,
            "text": "Use this visible material only.",
        }]}]

        bundle = MODULE.build_bundles([task], contexts, split="development", arm="candidate",
                                      hydration_artifact=READY_HYDRATION_ARTIFACT)[0]

        self.assertEqual([{"citationId": "CIT-001", "sourceVersion": "source:v1", "page": 2}],
                         bundle["citationOptions"])
        self.assertEqual([{
            "citationId": "CIT-001", "anchorId": "visible-a",
            "sourceVersion": "source:v1", "page": 2,
        }], bundle["citationResolution"])
        self.assertIn("citationId=CIT-001; sourceVersion=source:v1; page=2", bundle["prompt"])
        self.assertNotIn("visible-a", bundle["prompt"])
        self.assertNotIn("PRIVATE-EVALUATOR-ANCHOR", bundle["prompt"])


if __name__ == "__main__":
    unittest.main()
