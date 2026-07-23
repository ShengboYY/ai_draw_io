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
                "imagePaths": ["route.png"], "imageSha256s": [digest],
            }

            request = MODULE.request_body(bundle, root, "gpt-5.5", 6000)

        self.assertEqual("gpt-5.5", request["model"])
        self.assertEqual(6000, request["max_completion_tokens"])
        self.assertEqual("json_schema", request["response_format"]["type"])
        self.assertEqual("Return editable draw.io XML.", request["messages"][0]["content"][0]["text"])
        self.assertTrue(request["messages"][0]["content"][1]["image_url"]["url"].startswith(
            "data:image/png;base64,"))

    def test_rejects_malformed_generation_content(self):
        with self.assertRaises(json.JSONDecodeError):
            MODULE.response_payload({"choices": [{"message": {"content": "not JSON"}}]})
        with self.assertRaisesRegex(ValueError, "expected XML/citations"):
            MODULE.response_payload({"choices": [{"message": {"content": json.dumps({"xml": "<mxGraphModel/>"})}}]})
        with self.assertRaisesRegex(ValueError, "expected XML/citations"):
            MODULE.response_payload({"choices": [{"message": {"content": "[]"}}]})

    def test_rejects_tampered_prompt_or_non_frozen_provider_contract(self):
        bundle = {"taskId": "task-1", "prompt": "Frozen", "promptSha256": "a" * 64,
                  "imagePaths": [], "imageSha256s": []}

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
                "imagePaths": [], "imageSha256s": [],
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
                "imagePaths": [], "imageSha256s": [],
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


if __name__ == "__main__":
    unittest.main()
