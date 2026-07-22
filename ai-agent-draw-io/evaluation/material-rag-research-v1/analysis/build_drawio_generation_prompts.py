#!/usr/bin/env python3
"""Build evidence-grounded draw.io generation prompts without exposing evaluator gold."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def build_prompt(task: dict, context: dict) -> str:
    """Render one task and its hydrated context as the model-visible contract."""
    evidence_lines = []
    for evidence in context.get("evidence", []):
        # Citation metadata remains beside the excerpt so model output is independently traceable.
        artifact = "\nAttached visual artifact for this evidence." if evidence.get("imagePath") else ""
        evidence_lines.append(
            f"[{evidence['anchorId']} | {evidence['sourceVersion']} | page {evidence['page']}]\n"
            f"{evidence['text']}{artifact}"
        )
    material = "\n\n".join(evidence_lines) or "(No material was retrieved.)"
    input_xml = task.get("inputXml")
    edit_material = f"\n\nExisting editable XML to modify:\n{input_xml}" if input_xml else ""
    return (
        "Return JSON only with keys xml and citations. xml must be editable draw.io XML "
        "using mxGraphModel/mxCell. citations must be an array of objects with anchorId, "
        "sourceVersion and page. Use only the material below; do not invent material-backed "
        "claims or citations.\n\n"
        f"Task: {task['request']}{edit_material}\n\n"
        f"Retrieved material:\n{material}"
    )


def image_paths(context: dict) -> list[str]:
    """Keep each local visual artifact once so the multimodal runner can attach it exactly once."""
    return list(dict.fromkeys(
        evidence["imagePath"] for evidence in context.get("evidence", []) if evidence.get("imagePath")
    ))


def build_bundles(tasks: list[dict], contexts: list[dict], split: str, arm: str) -> list[dict]:
    """Pair each task with exactly one frozen context bundle from the selected experiment arm."""
    selected = {}
    for context in contexts:
        if context.get("arm") != arm:
            continue
        task_id = context["taskId"]
        if task_id in selected:
            raise ValueError(f"duplicate {arm} context for {task_id}")
        selected[task_id] = context
    bundles = []
    for task in tasks:
        if task.get("split") != split:
            continue
        context = selected.get(task["taskId"])
        if context is None:
            raise ValueError(f"missing {arm} context for {task['taskId']}")
        allowed_sources = set(task.get("allowedSourceVersions", [task.get("sourceVersion")]))
        for evidence in context.get("evidence", []):
            if evidence["sourceVersion"] not in allowed_sources:
                raise ValueError(f"out-of-scope evidence for {task['taskId']}")
        prompt = build_prompt(task, context)
        bundles.append({
            "taskId": task["taskId"],
            "arm": arm,
            "prompt": prompt,
            "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
            "evidence": context.get("evidence", []),
            "imagePaths": image_paths(context),
            "imageSha256s": list(dict.fromkeys(
                evidence["imageSha256"] for evidence in context.get("evidence", [])
                if evidence.get("imageSha256")
            )),
        })
    return bundles


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--contexts", type=Path, required=True)
    parser.add_argument("--split", choices=("development", "validation", "holdout"), required=True)
    parser.add_argument("--arm", choices=("control", "candidate", "fixed"), required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    tasks = json.loads(args.tasks.read_text())["tasks"]
    contexts = json.loads(args.contexts.read_text())["contexts"]
    result = {
        "split": args.split,
        "arm": args.arm,
        "bundles": build_bundles(tasks, contexts, args.split, args.arm),
    }
    args.json_out.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
