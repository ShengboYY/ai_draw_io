import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("run_drawio_generation.py")
SPEC = importlib.util.spec_from_file_location("drawio_generation_runner", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DrawioGenerationRunnerTest(unittest.TestCase):
    def test_builds_strict_multimodal_request_from_the_frozen_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "route.png"
            image.write_bytes(b"synthetic-image")
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            prompt = "Return editable draw.io XML."
            bundle = {
                "taskId": "task-1", "prompt": prompt,
                "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
                "evidence": [{"anchorId": "route-a", "sourceVersion": "architecture:v1", "page": 3}],
                "imagePaths": ["route.png"], "imageSha256s": [digest],
                "citationOptions": [{"anchorId": "route-a", "sourceVersion": "architecture:v1", "page": 3}],
            }

            request = MODULE.request_body(bundle, root, "gpt-5.5", 6000)

        self.assertEqual("gpt-5.5", request["model"])
        self.assertEqual(6000, request["max_completion_tokens"])
        self.assertEqual("json_schema", request["response_format"]["type"])
        citation_schema = request["response_format"]["json_schema"]["schema"]["properties"]["citations"]["items"]
        self.assertEqual(["route-a"], citation_schema["anyOf"][0]["properties"]["anchorId"]["enum"])
        self.assertEqual(["architecture:v1"], citation_schema["anyOf"][0]["properties"]["sourceVersion"]["enum"])
        self.assertEqual([3], citation_schema["anyOf"][0]["properties"]["page"]["enum"])
        self.assertEqual("json_schema:drawio_generation_response_v2", MODULE.response_format_name())
        self.assertEqual("Return editable draw.io XML.", request["messages"][0]["content"][0]["text"])
        self.assertTrue(request["messages"][0]["content"][1]["image_url"]["url"].startswith(
            "data:image/png;base64,"))

    def test_rejects_malformed_generation_content(self):
        bundle = {"taskId": "task-1", "citationOptions": []}
        with self.assertRaises(json.JSONDecodeError):
            MODULE.response_payload({"choices": [{"message": {"content": "not JSON"}}]}, bundle)
        with self.assertRaisesRegex(ValueError, "expected XML/citations"):
            MODULE.response_payload({"choices": [{"message": {"content": json.dumps({"xml": "<mxGraphModel/>"})}}]}, bundle)
        with self.assertRaisesRegex(ValueError, "expected XML/citations"):
            MODULE.response_payload({"choices": [{"message": {"content": "[]"}}]}, bundle)

    def test_rejects_tampered_prompt_or_non_frozen_provider_contract(self):
        bundle = {"taskId": "task-1", "prompt": "Frozen", "promptSha256": "a" * 64,
                  "imagePaths": [], "imageSha256s": [], "citationOptions": []}

        with self.assertRaisesRegex(ValueError, "prompt hash mismatch"):
            MODULE.request_body(bundle, Path.cwd(), "gpt-5.5", 6000)
        with self.assertRaisesRegex(ValueError, "frozen to OpenAI"):
            MODULE.verify_frozen_openai_contract("https://proxy.example", "v1/chat/completions", "gpt-5.5")
        with self.assertRaisesRegex(ValueError, "frozen to OpenAI"):
            MODULE.verify_frozen_openai_contract("https://api.openai.com", "v1/chat/completions", "gpt-5.4")

    def test_preflight_rejects_every_invalid_bundle_before_calling_the_provider(self):
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / "results") as directory:
            root = Path(directory)
            bundle_file = root / "bundles.json"
            bundle_file.write_text(json.dumps({"split": "development", "arm": "control", "bundles": [{
                "taskId": "task-1", "prompt": "tampered", "promptSha256": "a" * 64,
                "imagePaths": [], "imageSha256s": [], "citationOptions": [],
            }]}))
            original = MODULE.call
            MODULE.call = lambda *_args: self.fail("provider must not be called before preflight")
            try:
                with self.assertRaisesRegex(ValueError, "prompt hash mismatch"):
                    MODULE.run(bundle_file, root / "responses.json", root / "manifest.json", MODULE.ROOT,
                               "key", "https://api.openai.com", "v1/chat/completions", "gpt-5.5", 6000,
                               "11057404")
            finally:
                MODULE.call = original

    def test_preflight_rejects_an_output_that_would_overwrite_a_frozen_bundle(self):
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT / "results") as directory:
            root = Path(directory)
            bundle_file = root / "bundles.json"
            prompt = "Frozen"
            bundle_file.write_text(json.dumps({"split": "development", "arm": "control", "bundles": [{
                "taskId": "task-1", "prompt": prompt,
                "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
                "imagePaths": [], "imageSha256s": [], "citationOptions": [],
            }]}))
            original = MODULE.call
            MODULE.call = lambda *_args: self.fail("provider must not be called before output preflight")
            try:
                with self.assertRaisesRegex(ValueError, "must not alias frozen input"):
                    MODULE.run(bundle_file, bundle_file, root / "manifest.json", MODULE.ROOT,
                               "key", "https://api.openai.com", "v1/chat/completions", "gpt-5.5", 6000,
                               "11057404")
            finally:
                MODULE.call = original

    def test_rejects_a_cell_id_or_wrong_location_that_is_not_a_frozen_citation_option(self):
        bundle = {
            "taskId": "task-1", "prompt": "Frozen",
            "promptSha256": hashlib.sha256(b"Frozen").hexdigest(),
            "imagePaths": [], "imageSha256s": [],
            "citationOptions": [{"anchorId": "route-a", "sourceVersion": "architecture:v1", "page": 3}],
        }
        for citation in (
                {"anchorId": "scope", "sourceVersion": "architecture:v1", "page": 3},
                {"anchorId": "route-a", "sourceVersion": "architecture:v1", "page": 4}):
            body = {"choices": [{"message": {"content": json.dumps({
                "xml": "<mxGraphModel/>", "citations": [citation],
            })}}]}
            with self.subTest(citation=citation), self.assertRaisesRegex(
                ValueError, "frozen evidence citation option"):
                MODULE.response_payload(body, bundle)

    def test_requires_empty_citations_when_no_evidence_is_visible(self):
        prompt = "Return an editable layout without evidence."
        bundle = {
            "taskId": "layout", "prompt": prompt,
            "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
            "evidence": [], "citationOptions": [], "imagePaths": [], "imageSha256s": [],
        }

        request = MODULE.request_body(bundle, Path.cwd(), "gpt-5.5", 6000)

        citations = request["response_format"]["json_schema"]["schema"]["properties"]["citations"]
        self.assertEqual(0, citations["maxItems"])


if __name__ == "__main__":
    unittest.main()
