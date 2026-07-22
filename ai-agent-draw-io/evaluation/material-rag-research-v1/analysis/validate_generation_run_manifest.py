#!/usr/bin/env python3
"""Validate reproducibility metadata for a draw.io generation-model run."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{7,40}$")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate(manifest: dict, root: Path | None = None) -> dict:
    """Allow historical diagnostics, but reserve formal eligibility for complete manifests."""
    errors: list[str] = []
    if manifest.get("schemaVersion") != "material-rag-generation-run-manifest-v1":
        errors.append("unsupported schemaVersion")
    if manifest.get("split") != "development":
        errors.append("only development runs may be recorded before promotion")
    if not COMMIT.fullmatch(str(manifest.get("gitCommit", ""))):
        errors.append("gitCommit must be a 7-40 character lowercase hex commit")
    if manifest.get("qualification") not in {"diagnostic", "formal"}:
        errors.append("qualification must be diagnostic or formal")
    resolved_artifacts: dict[str, Path] = {}
    for artifact in manifest.get("artifacts", []):
        if not artifact.get("path") or not SHA256.fullmatch(str(artifact.get("sha256", ""))):
            errors.append("each artifact requires path and sha256")
        elif root is not None:
            path = (root / artifact["path"]).resolve()
            if not path.is_relative_to(root.resolve()) or not path.is_file():
                errors.append(f"artifact is missing or outside the research root: {artifact['path']}")
            elif file_sha256(path) != artifact["sha256"]:
                errors.append(f"artifact hash mismatch: {artifact['path']}")
            elif artifact.get("role"):
                resolved_artifacts[artifact["role"]] = path

    required_formal = {
        "corpusLockSha256": manifest.get("corpusLockSha256"),
        "taskFixtureSha256": manifest.get("taskFixtureSha256"),
        "promptBundlesSha256": manifest.get("promptBundlesSha256"),
        "responsesSha256": manifest.get("responsesSha256"),
        "provider": manifest.get("model", {}).get("provider"),
        "model": manifest.get("model", {}).get("name"),
        "endpointFingerprint": manifest.get("model", {}).get("endpointFingerprint"),
        "requestParameters": manifest.get("requestParameters"),
        "artifacts": manifest.get("artifacts"),
        "taskIds": manifest.get("taskIds"),
        "calls": manifest.get("calls"),
    }
    missing = [name for name, value in required_formal.items() if value in (None, "", [], {})]
    for name in ("corpusLockSha256", "taskFixtureSha256", "promptBundlesSha256", "responsesSha256"):
        value = required_formal[name]
        if value not in (None, "") and not SHA256.fullmatch(str(value)):
            errors.append(f"{name} must be sha256")
    parameters = manifest.get("requestParameters")
    if parameters:
        if any(name not in parameters for name in ("temperature", "maxCompletionTokens", "responseFormat")):
            errors.append("requestParameters must freeze temperature, maxCompletionTokens and responseFormat")
        if not isinstance(parameters.get("maxCompletionTokens"), int) \
                or parameters.get("maxCompletionTokens", 0) < 1:
            errors.append("maxCompletionTokens must be a positive integer")
    call_ids = [call.get("taskId") for call in manifest.get("calls", [])]
    if len(set(call_ids)) != len(call_ids):
        errors.append("calls contain duplicate task IDs")
    task_ids = manifest.get("taskIds", [])
    if len(set(task_ids)) != len(task_ids):
        errors.append("taskIds contain duplicates")
    if task_ids and set(call_ids) != set(task_ids):
        errors.append("calls must cover every frozen task ID exactly once")
    if manifest.get("qualification") == "formal":
        role_fields = {
            "corpusLock": "corpusLockSha256",
            "taskFixture": "taskFixtureSha256",
            "promptBundles": "promptBundlesSha256",
            "responses": "responsesSha256",
        }
        artifacts = manifest.get("artifacts", [])
        roles = [artifact.get("role") for artifact in artifacts]
        if any(roles.count(role) != 1 for role in role_fields):
            errors.append("formal artifacts require exactly one corpusLock, taskFixture, promptBundles and responses role")
        for role, field in role_fields.items():
            matches = [artifact for artifact in artifacts if artifact.get("role") == role]
            if len(matches) == 1 and matches[0].get("sha256") != manifest.get(field):
                errors.append(f"{field} does not match the {role} artifact")
        if root is not None and all(role in resolved_artifacts for role in role_fields):
            try:
                tasks_payload = json.loads(resolved_artifacts["taskFixture"].read_text())
                prompts_payload = json.loads(resolved_artifacts["promptBundles"].read_text())
                responses_payload = json.loads(resolved_artifacts["responses"].read_text())
                fixture_ids = {task["taskId"] for task in tasks_payload["tasks"]
                               if task.get("split") == manifest.get("split")}
                bundle_list = prompts_payload["bundles"]
                response_list = responses_payload["responses"]
                bundle_ids = [bundle["taskId"] for bundle in bundle_list]
                response_ids = [response["taskId"] for response in response_list]
                bundles = {bundle["taskId"]: bundle for bundle in bundle_list}
            except (KeyError, TypeError, json.JSONDecodeError):
                errors.append("role-specific artifacts do not have the expected JSON shape")
            else:
                if len(set(bundle_ids)) != len(bundle_ids) or len(set(response_ids)) != len(response_ids):
                    errors.append("prompt bundles and responses must not contain duplicate task IDs")
                if fixture_ids != set(task_ids) or set(bundle_ids) != set(task_ids) \
                        or set(response_ids) != set(task_ids):
                    errors.append("taskIds do not match task fixture, prompt bundles and responses")
                call_prompts = {call["taskId"]: call.get("promptSha256")
                                for call in manifest.get("calls", [])}
                if any(bundle.get("promptSha256") != call_prompts.get(task_id)
                       for task_id, bundle in bundles.items()):
                    errors.append("per-call prompt hashes do not match the prompt bundle")
    for call in manifest.get("calls", []):
        required_call = ("taskId", "status", "attempts", "httpStatus", "requestId",
                         "promptSha256", "usage", "latencyMs")
        if any(call.get(field) in (None, "", {}) for field in required_call):
            errors.append(f"incomplete call record for {call.get('taskId', 'unknown')}")
        if call.get("promptSha256") and not SHA256.fullmatch(str(call["promptSha256"])):
            errors.append(f"invalid promptSha256 for {call.get('taskId', 'unknown')}")
        if call.get("status") not in {"success", "error"}:
            errors.append(f"invalid status for {call.get('taskId', 'unknown')}")
        if not isinstance(call.get("httpStatus"), int):
            errors.append(f"invalid httpStatus for {call.get('taskId', 'unknown')}")
        usage = call.get("usage", {})
        if any(not isinstance(usage.get(field), int) or usage[field] < 0
               for field in ("inputTokens", "outputTokens")):
            errors.append(f"invalid token usage for {call.get('taskId', 'unknown')}")
        if not isinstance(call.get("attempts"), int) or call.get("attempts", 0) < 1:
            errors.append(f"invalid attempts for {call.get('taskId', 'unknown')}")
        if not isinstance(call.get("latencyMs"), int) or call.get("latencyMs", -1) < 0:
            errors.append(f"invalid latency for {call.get('taskId', 'unknown')}")
    if manifest.get("qualification") == "formal" and missing:
        errors.append("formal manifest is missing reproducibility fields: " + ", ".join(missing))
    formal_eligible = not errors and not missing and manifest.get("qualification") == "formal"
    return {
        "valid": not errors,
        "formalEligible": formal_eligible,
        "missingFormalFields": missing,
        "errors": errors,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    result = validate(json.loads(args.manifest.read_text()), args.manifest.parent.parent)
    if args.json_out:
        args.json_out.write_text(json.dumps(result, indent=2) + "\n")
    else:
        print(json.dumps(result, indent=2))
    if not result["valid"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
