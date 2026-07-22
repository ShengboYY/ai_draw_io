#!/usr/bin/env python3
"""Select a citation-preserving context bundle from an already frozen top-40."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def select(candidates: list[dict], limit: int = 8) -> list[dict]:
    """Keep rank order, drop duplicate chunks, then give each source one early slot."""
    unique = []
    seen_chunks = set()
    for candidate in candidates:
        if candidate["chunkId"] not in seen_chunks:
            unique.append(candidate)
            seen_chunks.add(candidate["chunkId"])
    selected, seen_sources = [], set()
    for candidate in unique:
        if candidate["sourceVersion"] not in seen_sources:
            selected.append(candidate)
            seen_sources.add(candidate["sourceVersion"])
        if len(selected) == limit:
            return selected
    for candidate in unique:
        if candidate not in selected:
            selected.append(candidate)
        if len(selected) == limit:
            break
    return selected


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    raw = json.loads(args.raw.read_text())
    bundles = [{"caseId": case["caseId"], "context": select(case["candidates"])}
               for case in raw["metrics"]["caseResults"]]
    args.json_out.write_text(json.dumps({"selector": "source-aware-top8-v1", "bundles": bundles}, indent=2) + "\n")


if __name__ == "__main__":
    main()
