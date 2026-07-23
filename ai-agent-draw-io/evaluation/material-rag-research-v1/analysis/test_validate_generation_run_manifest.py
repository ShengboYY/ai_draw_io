import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("validate_generation_run_manifest.py")
SPEC = importlib.util.spec_from_file_location("run_manifest", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GenerationRunManifestTest(unittest.TestCase):
    def test_accepts_diagnostic_but_does_not_call_it_formal(self):
        result = MODULE.validate({
            "schemaVersion": "material-rag-generation-run-manifest-v1",
            "qualification": "diagnostic",
            "split": "development",
            "gitCommit": "d019832f",
            "artifacts": [],
        })

        self.assertTrue(result["valid"])
        self.assertFalse(result["formalEligible"])
        self.assertIn("calls", result["missingFormalFields"])

    def test_accepts_validation_manifest_after_promotion(self):
        result = MODULE.validate({
            "schemaVersion": "material-rag-generation-run-manifest-v1",
            "qualification": "diagnostic",
            "split": "validation",
            "gitCommit": "d019832f",
            "artifacts": [],
        })

        self.assertTrue(result["valid"])
        self.assertFalse(result["formalEligible"])

    def test_rejects_formal_run_with_missing_reproducibility_fields(self):
        result = MODULE.validate({
            "schemaVersion": "material-rag-generation-run-manifest-v1",
            "qualification": "formal",
            "split": "development",
            "gitCommit": "d019832f",
            "artifacts": [],
        })

        self.assertFalse(result["valid"])
        self.assertTrue(any("formal manifest is missing" in error for error in result["errors"]))

    def test_accepts_complete_formal_development_run(self):
        digest = "a" * 64
        result = MODULE.validate({
            "schemaVersion": "material-rag-generation-run-manifest-v1",
            "qualification": "formal",
            "split": "development",
            "gitCommit": "d019832f",
            "corpusLockSha256": digest,
            "taskFixtureSha256": digest,
            "promptBundlesSha256": digest,
            "responsesSha256": digest,
            "model": {"provider": "provider", "name": "model", "endpointFingerprint": "api-v1"},
            "requestParameters": {"temperature": 0, "maxCompletionTokens": 4000,
                                  "responseFormat": "json_object"},
            "artifacts": [
                {"role": "corpusLock", "path": "corpus-lock.json", "sha256": digest},
                {"role": "taskFixture", "path": "tasks.json", "sha256": digest},
                {"role": "promptBundles", "path": "prompts.json", "sha256": digest},
                {"role": "responses", "path": "responses.json", "sha256": digest},
            ],
            "taskIds": ["task-1"],
            "calls": [{"taskId": "task-1", "status": "success", "attempts": 1,
                       "httpStatus": 200, "requestId": "redacted-sha256:abc",
                       "promptSha256": digest, "usage": {"inputTokens": 10, "outputTokens": 20},
                       "latencyMs": 100}],
        })

        self.assertTrue(result["valid"])
        self.assertTrue(result["formalEligible"])

    def test_rejects_top_level_hash_that_does_not_match_artifact_role(self):
        digest = "a" * 64
        manifest = {
            "schemaVersion": "material-rag-generation-run-manifest-v1", "qualification": "formal",
            "split": "development", "gitCommit": "d019832f",
            "corpusLockSha256": "b" * 64, "taskFixtureSha256": digest,
            "promptBundlesSha256": digest, "responsesSha256": digest,
            "model": {"provider": "provider", "name": "model", "endpointFingerprint": "api-v1"},
            "requestParameters": {"temperature": 0, "maxCompletionTokens": 1,
                                  "responseFormat": "json_object"},
            "artifacts": [
                {"role": "corpusLock", "path": "lock", "sha256": digest},
                {"role": "taskFixture", "path": "tasks", "sha256": digest},
                {"role": "promptBundles", "path": "prompts", "sha256": digest},
                {"role": "responses", "path": "responses", "sha256": digest},
            ],
            "taskIds": ["task-1"],
            "calls": [{"taskId": "task-1", "status": "success", "attempts": 1,
                       "httpStatus": 200, "requestId": "request", "promptSha256": digest,
                       "usage": {"inputTokens": 1, "outputTokens": 1}, "latencyMs": 1}],
        }

        result = MODULE.validate(manifest)

        self.assertFalse(result["formalEligible"])
        self.assertTrue(any("corpusLockSha256 does not match" in error for error in result["errors"]))

    def test_rejects_duplicate_prompt_bundle_task_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def write(name, payload):
                path = root / name
                path.write_text(json.dumps(payload))
                return path

            lock = write("lock.json", {"status": "frozen"})
            tasks = write("tasks.json", {"tasks": [{"taskId": "task-1", "split": "development"}]})
            prompts = write("prompts.json", {"bundles": [
                {"taskId": "task-1", "promptSha256": "a" * 64},
                {"taskId": "task-1", "promptSha256": "a" * 64},
            ]})
            responses = write("responses.json", {"responses": [{"taskId": "task-1"}]})
            paths = {"corpusLock": lock, "taskFixture": tasks,
                     "promptBundles": prompts, "responses": responses}
            hashes = {role: MODULE.file_sha256(path) for role, path in paths.items()}
            manifest = {
                "schemaVersion": "material-rag-generation-run-manifest-v1", "qualification": "formal",
                "split": "development", "gitCommit": "d019832f",
                "corpusLockSha256": hashes["corpusLock"], "taskFixtureSha256": hashes["taskFixture"],
                "promptBundlesSha256": hashes["promptBundles"], "responsesSha256": hashes["responses"],
                "model": {"provider": "provider", "name": "model", "endpointFingerprint": "api-v1"},
                "requestParameters": {"temperature": 0, "maxCompletionTokens": 1,
                                      "responseFormat": "json_object"},
                "artifacts": [{"role": role, "path": path.name, "sha256": hashes[role]}
                              for role, path in paths.items()],
                "taskIds": ["task-1"],
                "calls": [{"taskId": "task-1", "status": "success", "attempts": 1,
                           "httpStatus": 200, "requestId": "request", "promptSha256": "a" * 64,
                           "usage": {"inputTokens": 1, "outputTokens": 1}, "latencyMs": 1}],
            }

            result = MODULE.validate(manifest, root)

        self.assertFalse(result["formalEligible"])
        self.assertTrue(any("duplicate task IDs" in error for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
