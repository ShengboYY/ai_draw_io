#!/usr/bin/env python3
"""Export a task-complete paired E6b context from a hydrated retrieval trace."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# Allow direct script execution and isolated unit-test imports to share the selector.
sys.path.insert(0, str(Path(__file__).parent))
from select_drawio_context import select


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    """Return an artifact digest without trusting a caller-provided value."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def allowed_sources(task: dict, chartbook_sources: set[str]) -> set[str]:
    """Resolve the task's declared source policy without broadening explicit selection."""
    mode = task.get("sourceScopeMode")
    if mode == "selected_only":
        selected = str(task.get("selectedMaterialVersion", "")).strip()
        if not selected or chartbook_sources and selected not in chartbook_sources:
            raise ValueError(f"selected source is not mounted for {task.get('taskId', 'unknown')}")
        return {selected}
    if mode == "chartbook_auto":
        if task.get("selectedMaterialVersion"):
            raise ValueError(f"automatic source task declares an explicit selection: {task.get('taskId', 'unknown')}")
        if not chartbook_sources:
            raise ValueError(f"automatic source task has no mounted chartbook: {task.get('taskId', 'unknown')}")
        return set(chartbook_sources)
    if mode == "none":
        return set()
    if mode is not None:
        raise ValueError(f"unknown source scope mode for {task.get('taskId', 'unknown')}")
    # Historical fixtures predate sourceScopeMode and retain their original mounted-scope behavior.
    return set(task.get("allowedSourceVersions", [task["sourceVersion"]])) | chartbook_sources


def evidence_for_context(candidates: list[dict], task: dict, artifact_root: Path,
                         chartbook_sources: set[str], anchors: dict[str, dict] | None,
                         requires_artifact: bool) -> list[dict]:
    """Flatten selected hydrated chunks and reject untraceable multimodal material."""
    evidence, seen = [], set()
    permitted = allowed_sources(task, chartbook_sources)
    for candidate in candidates:
        if candidate.get("sourceVersion") not in permitted:
            raise ValueError(f"out-of-scope candidate for {task['taskId']}")
        for item in candidate.get("evidence", []):
            if not str(item.get("text", "")).strip():
                raise ValueError(f"blank evidence text for {task['taskId']}")
            if item.get("sourceVersion") not in permitted:
                raise ValueError(f"out-of-scope evidence for {task['taskId']}")
            if item["sourceVersion"] != candidate["sourceVersion"]:
                raise ValueError(f"candidate/evidence source mismatch for {task['taskId']}")
            if not item.get("anchorId") or not isinstance(item.get("page"), int):
                raise ValueError(f"unlocatable evidence for {task['taskId']}")
            if (candidate.get("page") != item["page"]
                    or candidate.get("retrievalTextSha256")
                    != hashlib.sha256(item["text"].encode()).hexdigest()):
                raise ValueError(f"candidate evidence provenance mismatch for {task['taskId']}")
            if item["anchorId"].startswith("retrieved:"):
                if item["anchorId"] != f"retrieved:{candidate['chunkId']}":
                    raise ValueError(f"retrieved citation ID mismatch for {task['taskId']}")
            elif anchors is not None:
                anchor = anchors.get(item["anchorId"])
                expected_source = f"{anchor['source']}:{anchor['version']}" if anchor else None
                if (anchor is None or expected_source != item["sourceVersion"]
                        or anchor.get("page") != item["page"] or anchor.get("split") != task["split"]):
                    raise ValueError(f"anchor provenance mismatch for {task['taskId']}")
            image_path, image_hash = item.get("imagePath"), item.get("imageSha256")
            if bool(image_path) != bool(image_hash):
                raise ValueError(f"incomplete image provenance for {task['taskId']}")
            if image_path:
                artifact = (artifact_root / image_path).resolve()
                if artifact_root not in artifact.parents or not artifact.is_file():
                    raise ValueError(f"missing or escaped image artifact for {task['taskId']}")
                if sha256(artifact) != image_hash:
                    raise ValueError(f"image hash mismatch for {task['taskId']}")
            # The same hydrated anchor need appear only once in a model context.
            key = (item["anchorId"], item["sourceVersion"], item["page"], item["text"], image_path)
            if key not in seen:
                seen.add(key)
                evidence.append({
                    "anchorId": item["anchorId"], "sourceVersion": item["sourceVersion"],
                    "page": item["page"], "text": item["text"], **(
                        {"imagePath": image_path, "imageSha256": image_hash} if image_path else {}
                    ),
                    "chunkId": candidate["chunkId"], "rank": candidate["rank"],
                })
    if requires_artifact and not any(
            item.get("imagePath") and item["sourceVersion"] == task["sourceVersion"]
            for item in evidence):
        raise ValueError(f"missing required visual/OCR artifact for {task['taskId']}")
    return evidence


def required_evidence_readiness(tasks: list[dict], contexts: list[dict], no_retrieval_task_ids: set[str]) -> dict:
    """Require the treatment to be answerable while measuring control evidence loss as an outcome."""
    by_context = {(context["taskId"], context["arm"]): context for context in contexts}
    task_reports = []
    for task in tasks:
        if task["taskId"] in no_retrieval_task_ids:
            continue
        required = sorted(task.get("citationAssertions", {}).get("mustCiteAnchors", []))
        missing = {}
        for arm in ("control", "candidate"):
            visible = {item["anchorId"] for item in by_context[(task["taskId"], arm)]["evidence"]}
            missing[arm] = sorted(set(required) - visible)
        task_reports.append({"taskId": task["taskId"], "requiredAnchors": required,
                             "controlMissing": missing["control"], "candidateMissing": missing["candidate"]})
    control_ready = all(not report["controlMissing"] for report in task_reports)
    candidate_ready = all(not report["candidateMissing"] for report in task_reports)
    return {
        "policy": "candidate-required-control-measured-v2",
        "ready": candidate_ready,
        "controlReady": control_ready,
        "candidateReady": candidate_ready,
        "tasks": task_reports,
    }


def export(trace: dict, tasks: list[dict], split: str, limit: int,
           minimum_change_rate: float, artifact_root: Path,
           chartbook_sources: set[str] | None = None,
           anchors: dict[str, dict] | None = None, candidate_pool_size: int = 40,
           artifact_task_ids: set[str] | None = None,
           no_retrieval_task_ids: set[str] | None = None) -> dict:
    """Build both arms from one frozen trace and apply the E6b contrast gate."""
    if trace.get("schemaVersion") != "material-rag-drawio-task-hydration-candidates-v1":
        raise ValueError("unexpected hydration trace schema")
    run = trace.get("retrievalRun", {})
    if not all(str(run.get(field, "")).strip() for field in ("runId", "gitCommit", "corpusLockSha256")):
        raise ValueError("hydration trace lacks retrieval provenance")
    artifact_root = artifact_root.resolve()
    active = [task for task in tasks if task.get("split") == split]
    if not 1 <= limit <= candidate_pool_size:
        raise ValueError("context limit must be within the frozen candidate pool")
    chartbook_sources = chartbook_sources or set()
    artifact_task_ids = artifact_task_ids or set()
    no_retrieval_task_ids = no_retrieval_task_ids or set()
    trace_by_task = {item.get("taskId"): item for item in trace.get("tasks", [])}
    if len(trace_by_task) != len(trace.get("tasks", [])):
        raise ValueError("duplicate task hydration trace")
    active_ids = {task["taskId"] for task in active}
    if set(trace_by_task) != active_ids:
        raise ValueError("hydration trace does not cover exactly the active split tasks")

    contexts, summaries = [], []
    for task in active:
        candidates = trace_by_task[task["taskId"]].get("candidates", [])
        if task["taskId"] in no_retrieval_task_ids:
            if candidates:
                raise ValueError(f"no-retrieval task has hydrated candidates: {task['taskId']}")
            contexts.extend((
                {"taskId": task["taskId"], "arm": "control",
                 "allowedSourceVersions": sorted(allowed_sources(task, chartbook_sources)), "evidence": []},
                {"taskId": task["taskId"], "arm": "candidate",
                 "allowedSourceVersions": sorted(allowed_sources(task, chartbook_sources)), "evidence": []},
            ))
            summaries.append({"taskId": task["taskId"], "changed": False,
                              "rawCandidateChunkIds": [], "rawCandidateChunkIdsSha256": hashlib.sha256(b"[]").hexdigest(),
                              "controlChunkIds": [], "candidateChunkIds": []})
            continue
        if len(candidates) != candidate_pool_size:
            raise ValueError(f"candidate pool size is not {candidate_pool_size} for {task['taskId']}")
        for rank, candidate in enumerate(candidates, start=1):
            if candidate.get("rank") != rank or not candidate.get("chunkId"):
                raise ValueError(f"invalid rank order for {task['taskId']}")
        permitted = allowed_sources(task, chartbook_sources)
        if any(item.get("sourceVersion") not in permitted for item in candidates):
            raise ValueError(f"out-of-scope candidate for {task['taskId']}")
        scoped_candidates = candidates
        control = scoped_candidates[:limit]
        candidate = select(scoped_candidates, limit)
        control_ids = [item["chunkId"] for item in control]
        candidate_ids = [item["chunkId"] for item in candidate]
        contexts.extend((
            {"taskId": task["taskId"], "arm": "control",
             "allowedSourceVersions": sorted(allowed_sources(task, chartbook_sources)),
             "evidence": evidence_for_context(control, task, artifact_root, chartbook_sources, anchors,
                                              task["taskId"] in artifact_task_ids)},
            {"taskId": task["taskId"], "arm": "candidate",
             "allowedSourceVersions": sorted(allowed_sources(task, chartbook_sources)),
             "evidence": evidence_for_context(candidate, task, artifact_root, chartbook_sources, anchors,
                                              task["taskId"] in artifact_task_ids)},
        ))
        raw_chunk_ids = [item["chunkId"] for item in candidates]
        summaries.append({
            "taskId": task["taskId"], "changed": control_ids != candidate_ids,
            "rawCandidateChunkIds": raw_chunk_ids,
            "rawCandidateChunkIdsSha256": hashlib.sha256(
                json.dumps(raw_chunk_ids, separators=(",", ":")).encode()
            ).hexdigest(),
            "scopedCandidateCount": len(scoped_candidates),
            "controlChunkIds": control_ids, "candidateChunkIds": candidate_ids,
        })
    retrieval_required = [summary for summary in summaries if summary["taskId"] not in no_retrieval_task_ids]
    changed = sum(summary["changed"] for summary in retrieval_required)
    changed_rate = changed / len(retrieval_required) if retrieval_required else 0.0
    result = {
        "schemaVersion": "material-rag-drawio-paired-hydration-v1", "split": split,
        "selector": "source-aware-top8-v1", "candidatePoolSize": candidate_pool_size,
        "limit": limit, "taskCount": len(active), "retrievalRequiredTaskCount": len(retrieval_required),
        "changedTaskCount": changed, "changedTaskRate": changed_rate,
        "minimumChangeRate": minimum_change_rate,
        "effectiveExperiment": changed_rate >= minimum_change_rate,
        "hydrationCandidates": run, "contexts": contexts, "taskSummaries": summaries,
    }
    result["modelVisibleRequiredEvidence"] = required_evidence_readiness(
        active, contexts, no_retrieval_task_ids)
    if not result["effectiveExperiment"]:
        raise ValueError("paired hydration effectiveness gate failed")
    return result


def verify_provenance(trace: dict, corpus_lock: Path, tasks: Path, ground_truth: Path,
                      source_evidence_identities: Path) -> None:
    """Bind the live trace to this exact frozen corpus lock before export."""
    run = trace["retrievalRun"]
    if not re.fullmatch(r"[0-9a-f]{7,64}", run["gitCommit"]):
        raise ValueError("retrieval trace has an invalid Git commit")
    if not re.fullmatch(r"[0-9a-f]{64}", run["corpusLockSha256"]):
        raise ValueError("retrieval trace has an invalid corpus lock hash")
    if sha256(corpus_lock) != run["corpusLockSha256"]:
        raise ValueError("retrieval trace corpus lock does not match the frozen lock")
    lock = json.loads(corpus_lock.read_text())
    if lock.get("schemaVersion") != "material-rag-corpus-lock-v1" or lock.get("status") != "frozen":
        raise ValueError("corpus lock is not a frozen material-RAG lock")
    task_fixture_key = f"fixtures/{tasks.name}"
    if lock.get("provenanceFiles", {}).get(task_fixture_key) != sha256(tasks):
        raise ValueError("corpus lock does not bind the supplied task fixture")
    if lock.get("files", {}).get("ground-truth.json") != sha256(ground_truth):
        raise ValueError("corpus lock does not bind the supplied ground truth")
    source_reference = trace.get("sourceEvidenceIdentityManifest")
    if source_reference is not None:
        if source_reference.get("sha256") != sha256(source_evidence_identities):
            raise ValueError("retrieval trace source evidence identity manifest does not match")
        identities = json.loads(source_evidence_identities.read_text())
        if identities.get("schemaVersion") != "material-rag-source-evidence-identities-v1":
            raise ValueError("source evidence identity manifest has an unexpected schema")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--hydration-candidates", type=Path, required=True)
    parser.add_argument("--split", choices=("development", "validation"), default="development")
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path, default=ROOT)
    parser.add_argument("--ground-truth", type=Path, default=ROOT / "fixtures/generated/ground-truth.json")
    parser.add_argument("--source-evidence-identities", type=Path,
                        default=ROOT / "fixtures/generated/source-evidence-identities-v1.json")
    parser.add_argument("--corpus-lock", type=Path, default=ROOT / "fixtures/generated/corpus-lock.json")
    parser.add_argument("--require-model-visible-required-evidence", action="store_true")
    args = parser.parse_args()
    task_fixture = json.loads(args.tasks.read_text())
    prefix = "development" if args.split == "development" else "validation"
    chartbook_sources = set(task_fixture.get(f"{prefix}ChartbookSourceVersions", []))
    artifact_task_ids = set(task_fixture.get(f"{prefix}MultimodalArtifactTaskIds", []))
    no_retrieval_task_ids = set(task_fixture.get(f"{prefix}NoRetrievalTaskIds", []))
    anchor_by_id = {item["anchorId"]: item for item in json.loads(args.ground_truth.read_text())["anchors"]}
    trace = json.loads(args.hydration_candidates.read_text())
    verify_provenance(trace, args.corpus_lock, args.tasks, args.ground_truth, args.source_evidence_identities)
    result = export(trace, task_fixture["tasks"],
                    args.split, 8, 0.2,
                    args.artifact_root.resolve(), chartbook_sources, anchor_by_id,
                    40, artifact_task_ids, no_retrieval_task_ids)
    result["hydrationCandidates"]["path"] = args.hydration_candidates.as_posix()
    result["hydrationCandidates"]["sha256"] = sha256(args.hydration_candidates)
    result["groundTruth"] = {"path": args.ground_truth.as_posix(), "sha256": sha256(args.ground_truth)}
    if trace.get("sourceEvidenceIdentityManifest") is not None:
        result["sourceEvidenceIdentityManifest"] = {
            "path": args.source_evidence_identities.as_posix(),
            "sha256": sha256(args.source_evidence_identities),
        }
    result["taskFixture"] = {"path": args.tasks.as_posix(), "sha256": sha256(args.tasks)}
    result["corpusLock"] = {"path": args.corpus_lock.as_posix(), "sha256": sha256(args.corpus_lock)}
    result["exporterScriptSha256"] = sha256(Path(__file__))
    args.json_out.write_text(json.dumps(result, indent=2) + "\n")
    if args.require_model_visible_required_evidence and not result["modelVisibleRequiredEvidence"]["ready"]:
        raise SystemExit("model-visible required evidence readiness gate failed")


if __name__ == "__main__":
    main()
