#!/usr/bin/env python3
"""Run one frozen E7 prompt arm through an OpenAI-compatible Chat Completions endpoint."""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import mimetypes
import os
import re
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OPENAI_BASE_URL = "https://api.openai.com"
OPENAI_COMPLETIONS_PATH = "v1/chat/completions"
OPENAI_MODEL = "gpt-5.5"
JSON_SCHEMA = {
    "name": "drawio_generation_response_v2",
    "strict": True,
    "schema": {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "xml": {"type": "string"},
            "citations": {
                "type": "array",
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "anchorId": {"type": "string"},
                        "sourceVersion": {"type": "string"},
                        "page": {"type": "integer"},
                    },
                    "required": ["anchorId", "sourceVersion", "page"],
                },
            },
        },
        "required": ["xml", "citations"],
    },
}


def citation_options(bundle: dict) -> list[dict]:
    """Return the frozen evidence triples allowed in this model response."""
    options = bundle.get("citationOptions")
    if not isinstance(options, list):
        raise ValueError(f"citation options are missing for {bundle.get('taskId', 'unknown')}")
    triples = []
    for option in options:
        if not isinstance(option, dict) or not isinstance(option.get("anchorId"), str) \
                or not isinstance(option.get("sourceVersion"), str) or not isinstance(option.get("page"), int):
            raise ValueError(f"citation options are malformed for {bundle.get('taskId', 'unknown')}")
        triples.append((option["anchorId"], option["sourceVersion"], option["page"]))
    if len(set(triples)) != len(triples):
        raise ValueError(f"citation options are duplicated for {bundle.get('taskId', 'unknown')}")
    return options


def response_schema(bundle: dict) -> dict:
    """Constrain citation IDs to evidence supplied in the frozen prompt bundle."""
    schema = copy.deepcopy(JSON_SCHEMA)
    options = citation_options(bundle)
    citations = schema["schema"]["properties"]["citations"]
    if not options:
        citations["maxItems"] = 0
        return schema
    citations["items"] = {"anyOf": [
        {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "anchorId": {"type": "string", "enum": [option["anchorId"]]},
                "sourceVersion": {"type": "string", "enum": [option["sourceVersion"]]},
                "page": {"type": "integer", "enum": [option["page"]]},
            },
            "required": ["anchorId", "sourceVersion", "page"],
        }
        for option in options
    ]}
    return schema


def response_format_name() -> str:
    """Keep the manifest label coupled to the actual structured-output schema version."""
    return f"json_schema:{JSON_SCHEMA['name']}"


def sha256_bytes(value: bytes) -> str:
    """Return a stable digest used by the formal manifest."""
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def endpoint(base_url: str, completions_path: str) -> str:
    """Join explicitly configured API coordinates without logging credentials."""
    base = base_url.strip().rstrip("/")
    suffix = completions_path.strip().lstrip("/")
    if not base.startswith("https://") or not suffix:
        raise ValueError("generation endpoint must be an HTTPS base URL and a completion path")
    return f"{base}/{suffix}"


def verify_frozen_openai_contract(base_url: str, completions_path: str, model: str) -> str:
    """Reject alternate providers/models rather than mislabeling a formal E7 run as OpenAI GPT-5.5."""
    resolved = endpoint(base_url, completions_path)
    if resolved != f"{OPENAI_BASE_URL}/{OPENAI_COMPLETIONS_PATH}" or model != OPENAI_MODEL:
        raise ValueError("E7 r4 is frozen to OpenAI gpt-5.5 at v1/chat/completions")
    return resolved


def image_content(image_path: Path) -> dict:
    """Encode one frozen local artifact as an in-request data URL."""
    mime = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
    data = base64.b64encode(image_path.read_bytes()).decode("ascii")
    return {"type": "image_url", "image_url": {
        "url": f"data:{mime};base64,{data}", "detail": "high",
    }}


def validate_bundle(bundle: dict, artifact_root: Path) -> None:
    """Validate every frozen prompt/artifact before any API request leaves the workstation."""
    artifact_root = artifact_root.resolve()
    if not str(bundle.get("taskId", "")).strip() or not isinstance(bundle.get("prompt"), str):
        raise ValueError("prompt bundle requires taskId and prompt")
    if sha256_bytes(bundle["prompt"].encode()) != bundle.get("promptSha256"):
        raise ValueError(f"prompt hash mismatch for {bundle['taskId']}")
    visible_options = [
        {"anchorId": evidence["anchorId"], "sourceVersion": evidence["sourceVersion"], "page": evidence["page"]}
        for evidence in bundle.get("evidence", [])
    ]
    if citation_options(bundle) != [
            {"anchorId": anchor_id, "sourceVersion": source_version, "page": page}
            for anchor_id, source_version, page in sorted({
                (option["anchorId"], option["sourceVersion"], option["page"]) for option in visible_options
            })]:
        raise ValueError(f"citation options do not match visible evidence for {bundle['taskId']}")
    paths = bundle.get("imagePaths", [])
    hashes = bundle.get("imageSha256s", [])
    if not isinstance(paths, list) or not isinstance(hashes, list) or len(paths) != len(hashes):
        raise ValueError(f"image provenance is incomplete for {bundle['taskId']}")
    for relative_path, expected_hash in zip(paths, hashes):
        path = (artifact_root / relative_path).resolve()
        if artifact_root not in path.parents or not path.is_file() or sha256_file(path) != expected_hash:
            raise ValueError(f"image provenance mismatch for {bundle['taskId']}")


def request_body(bundle: dict, artifact_root: Path, model: str, max_completion_tokens: int) -> dict:
    """Build a model-visible request strictly from the frozen prompt bundle."""
    artifact_root = artifact_root.resolve()
    validate_bundle(bundle, artifact_root)
    content = [{"type": "text", "text": bundle["prompt"]}]
    for relative_path, expected_hash in zip(bundle["imagePaths"], bundle["imageSha256s"]):
        path = (artifact_root / relative_path).resolve()
        content.append(image_content(path))
    return {
        "model": model,
        "messages": [{"role": "user", "content": content}],
        "max_completion_tokens": max_completion_tokens,
        "reasoning_effort": "low",
        "response_format": {"type": "json_schema", "json_schema": response_schema(bundle)},
    }


def response_payload(body: dict, bundle: dict) -> tuple[str, list[dict]]:
    """Extract the strict JSON message content, treating malformed output as a scored failure."""
    content = body.get("choices", [{}])[0].get("message", {}).get("content", "")
    parsed = json.loads(content)
    if not isinstance(parsed, dict) or not isinstance(parsed.get("xml"), str) \
            or not isinstance(parsed.get("citations"), list):
        raise ValueError("response does not match the expected XML/citations contract")
    allowed = {
        (option["anchorId"], option["sourceVersion"], option["page"])
        for option in citation_options(bundle)
    }
    for citation in parsed["citations"]:
        if not isinstance(citation, dict) or (
                citation.get("anchorId"), citation.get("sourceVersion"), citation.get("page")) not in allowed:
            raise ValueError("response citation is not a frozen evidence citation option")
    return parsed["xml"], parsed["citations"]


def safe_error(body: bytes) -> str:
    """Keep a short provider diagnostic without persisting a full arbitrary response body."""
    try:
        parsed = json.loads(body)
        message = parsed.get("error", {}).get("message", "request failed")
    except (json.JSONDecodeError, AttributeError):
        message = body.decode("utf-8", errors="replace")
    return str(message).replace("\n", " ")[:500]


def call(endpoint_url: str, api_key: str, request: dict) -> tuple[int, str | None, dict, int, str | None]:
    """Make one bounded request and retain only response metadata needed for reproducibility."""
    started = time.monotonic()
    payload = json.dumps(request).encode("utf-8")
    http_request = urllib.request.Request(endpoint_url, data=payload, method="POST", headers={
        "Authorization": f"Bearer {api_key}", "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(http_request, timeout=180) as response:
            body = response.read()
            try:
                parsed = json.loads(body)
            except json.JSONDecodeError:
                return response.status, response.headers.get("x-request-id"), {}, \
                    round((time.monotonic() - started) * 1000), "provider returned invalid JSON"
            if not isinstance(parsed, dict):
                return response.status, response.headers.get("x-request-id"), {}, \
                    round((time.monotonic() - started) * 1000), "provider response is not a JSON object"
            return response.status, response.headers.get("x-request-id"), parsed, \
                round((time.monotonic() - started) * 1000), None
    except urllib.error.HTTPError as error:
        return error.code, error.headers.get("x-request-id"), {}, round((time.monotonic() - started) * 1000), \
            safe_error(error.read())
    except (urllib.error.URLError, TimeoutError) as error:
        return 0, None, {}, round((time.monotonic() - started) * 1000), str(error)[:500]


def run(bundle_file: Path, responses_out: Path, manifest_out: Path, artifact_root: Path,
        api_key: str, base_url: str, completions_path: str, model: str, max_completion_tokens: int,
        git_commit: str) -> None:
    """Run exactly one arm and write a validator-compatible formal manifest and normalized responses."""
    bundle_file, responses_out, manifest_out = (bundle_file.resolve(), responses_out.resolve(), manifest_out.resolve())
    research_root = ROOT.resolve()
    results_root = (ROOT / "results").resolve()
    if artifact_root != research_root:
        raise ValueError("formal E7 artifacts must be rooted in the frozen research directory")
    if research_root not in bundle_file.parents or not bundle_file.is_file():
        raise ValueError("prompt bundle must be a file inside the frozen research directory")
    for output in (responses_out, manifest_out):
        if results_root not in output.parents or (output.exists() and not output.is_file()):
            raise ValueError("responses and manifest outputs must be ordinary files inside results")
    bundles_payload = json.loads(bundle_file.read_text())
    bundles = bundles_payload.get("bundles", [])
    if bundles_payload.get("split") != "development" or bundles_payload.get("arm") not in {"control", "candidate"}:
        raise ValueError("only one frozen Development control or candidate bundle may be run")
    task_ids = [bundle.get("taskId") for bundle in bundles]
    if not task_ids or len(set(task_ids)) != len(task_ids):
        raise ValueError("prompt bundle task IDs must be present and unique")
    endpoint_url = verify_frozen_openai_contract(base_url, completions_path, model)
    if not re.fullmatch(r"[0-9a-f]{7,40}", git_commit):
        raise ValueError("git commit must be a lowercase 7-40 character hex value")
    # Complete deterministic validation and output checks before the first external request.
    input_artifacts = {bundle_file, (ROOT / "fixtures/generated/corpus-lock.json").resolve(),
                       (ROOT / "fixtures/drawio-generation-tasks-v2.json").resolve()}
    for bundle in bundles:
        validate_bundle(bundle, artifact_root)
        input_artifacts.update((artifact_root / relative_path).resolve()
                               for relative_path in bundle["imagePaths"])
    for required in (ROOT / "fixtures/generated/corpus-lock.json",
                     ROOT / "fixtures/drawio-generation-tasks-v2.json"):
        if not required.is_file():
            raise ValueError(f"required formal artifact is missing: {required}")
    if responses_out == manifest_out:
        raise ValueError("responses and manifest outputs must be different files")
    if responses_out in input_artifacts or manifest_out in input_artifacts:
        raise ValueError("responses and manifest outputs must not alias frozen input artifacts")
    for output in (responses_out, manifest_out):
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".generation-preflight-", delete=True):
            pass
    responses, calls = [], []
    for bundle in bundles:
        request = request_body(bundle, artifact_root, model, max_completion_tokens)
        http_status, request_id, body, latency_ms, error = call(endpoint_url, api_key, request)
        xml, citations, status = "", [], "error"
        if error is None and 200 <= http_status < 300:
            try:
                xml, citations = response_payload(body, bundle)
                status = "success"
            except (ValueError, json.JSONDecodeError, IndexError, TypeError) as parse_error:
                error = str(parse_error)[:500]
        usage = body.get("usage", {}) if isinstance(body.get("usage", {}), dict) else {}
        responses.append({"taskId": bundle["taskId"], "xml": xml, "citations": citations,
                          "httpStatus": http_status, "error": error})
        # Use the request ID header when available; the OpenAI body ID remains a safe opaque fallback.
        calls.append({"taskId": bundle["taskId"], "status": status, "attempts": 1,
                      "httpStatus": http_status, "requestId": request_id or str(body.get("id", "unavailable")),
                      "promptSha256": sha256_bytes(bundle["prompt"].encode()),
                      "usage": {"inputTokens": int(usage.get("prompt_tokens", 0)),
                                "outputTokens": int(usage.get("completion_tokens", 0))},
                      "latencyMs": latency_ms})
    responses_out.write_text(json.dumps({"responses": responses}, indent=2) + "\n")
    artifacts = [
        ("corpusLock", ROOT / "fixtures/generated/corpus-lock.json"),
        ("taskFixture", ROOT / "fixtures/drawio-generation-tasks-v2.json"),
        ("promptBundles", bundle_file),
        ("responses", responses_out),
    ]
    manifest = {
        "schemaVersion": "material-rag-generation-run-manifest-v1", "qualification": "formal",
        "split": "development", "gitCommit": git_commit,
        "corpusLockSha256": sha256_file(artifacts[0][1]), "taskFixtureSha256": sha256_file(artifacts[1][1]),
        "promptBundlesSha256": sha256_file(bundle_file), "responsesSha256": sha256_file(responses_out),
        "model": {"provider": "OpenAI", "name": model,
                  "endpointFingerprint": "sha256:" + sha256_bytes(endpoint_url.encode())},
        # GPT-5.5 uses its provider default temperature; the field is deliberately omitted from the API request.
        "requestParameters": {"temperature": "provider-default", "maxCompletionTokens": max_completion_tokens,
                              "responseFormat": response_format_name(),
                              "reasoningEffort": "low", "imageDetail": "high"},
        "taskIds": task_ids, "calls": calls,
        "artifacts": [{"role": role, "path": path.relative_to(ROOT).as_posix(), "sha256": sha256_file(path)}
                      for role, path in artifacts],
    }
    manifest_out.write_text(json.dumps(manifest, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt-bundles", type=Path, required=True)
    parser.add_argument("--responses-out", type=Path, required=True)
    parser.add_argument("--manifest-out", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path, default=ROOT)
    parser.add_argument("--model", default="gpt-5.5")
    parser.add_argument("--max-completion-tokens", type=int, default=6000)
    args = parser.parse_args()
    if args.max_completion_tokens < 1:
        raise ValueError("max completion tokens must be positive")
    api_key = os.environ.get("LLM_API_KEY", "")
    base_url = os.environ.get("LLM_BASE_URL", "")
    completions_path = os.environ.get("LLM_COMPLETIONS_PATH", "")
    git_commit = os.environ.get("MATERIAL_RAG_COMMIT_SHA", "")
    if not all((api_key, base_url, completions_path, git_commit)):
        raise ValueError("LLM_API_KEY, LLM_BASE_URL, LLM_COMPLETIONS_PATH and MATERIAL_RAG_COMMIT_SHA are required")
    run(args.prompt_bundles, args.responses_out, args.manifest_out, args.artifact_root.resolve(), api_key,
        base_url, completions_path, args.model, args.max_completion_tokens, git_commit)


if __name__ == "__main__":
    main()
