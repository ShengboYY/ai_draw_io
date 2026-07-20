#!/usr/bin/env python3
"""Measure deterministic OCR text error and anchor recall for the controlled scan cohort."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


FIXTURE_DIR = Path(__file__).resolve().parents[1] / "fixtures"
sys.path.insert(0, str(FIXTURE_DIR))
from realistic_corpus_specs import ADDITIONAL_FACTS, SCANNED_DOCUMENT  # noqa: E402


CARD_EXPECTED_PAGES = {
    1: """SCANNED OPERATIONS CARD
Document code: OCR-AX9-771
Emergency load threshold: 73%
Escalation owner: Night Operations Lead
Recovery window: 18 minutes
The nearby value 37% is a training example, not the threshold.""",
    2: """扫描审批卡
文件编号：OCR-ZH-882
安全阈值：62%
审批人：值班经理
复核时间：每周三 14:30
注意：26% 是演示数据，不是安全阈值。""",
    3: """SCANNED ZONE TABLE
Zone | Limit | Owner
North | 91 | Team Cedar
South | 84 | Team Birch
East | 76 | Team Maple
West | 68 | Team Ash""",
}

CARD_ANCHORS = {
    "ocr-en-code": (1, "OCR-AX9-771"),
    "ocr-en-threshold": (1, "73%"),
    "ocr-zh-code": (2, "OCR-ZH-882"),
    "ocr-zh-threshold": (2, "62%"),
    "ocr-zone-east": (3, "East|76|TeamMaple"),
}


def rail_profile() -> tuple[dict[int, str], dict[str, tuple[int, str]]]:
    """Derive OCR truth from the same authored scan specification used by the PDF generator."""
    pages = {
        page_no: "\n".join([
            page["title"],
            page["subtitle"],
            *(f"{heading}\n{body}" for heading, body in page["sections"]),
            f"Synthetic inspection scan | page {page_no}",
        ])
        for page_no, page in enumerate(SCANNED_DOCUMENT["pages"], start=1)
    }
    anchors = {
        fact["anchorId"]: (fact["page"], fact["goldMatch"])
        for fact in ADDITIONAL_FACTS
        if fact["source"] == SCANNED_DOCUMENT["source"] and fact["modality"].startswith("ocr")
    }
    return pages, anchors


def normalize(value: str) -> str:
    return re.sub(r"\s+", "", value).casefold().replace("：", ":").replace("，", ",")


def edit_distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for left_index, left_value in enumerate(left, start=1):
        current = [left_index]
        for right_index, right_value in enumerate(right, start=1):
            current.append(min(
                current[-1] + 1,
                previous[right_index] + 1,
                previous[right_index - 1] + (left_value != right_value),
            ))
        previous = current
    return previous[-1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("--profile", choices=["cards", "rail"], default="cards")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    expected_pages, anchors = (CARD_EXPECTED_PAGES, CARD_ANCHORS)
    if args.profile == "rail":
        expected_pages, anchors = rail_profile()
    pages: dict[int, str] = {}
    page_metrics = []
    total_distance = 0
    total_characters = 0
    for page_no, expected in expected_pages.items():
        actual = args.result_dir.joinpath(f"page-{page_no}.txt").read_text(encoding="utf-8")
        pages[page_no] = actual
        expected_normalized = normalize(expected)
        actual_normalized = normalize(actual)
        distance = edit_distance(expected_normalized, actual_normalized)
        total_distance += distance
        total_characters += len(expected_normalized)
        page_metrics.append({
            "page": page_no,
            "characters": len(expected_normalized),
            "editDistance": distance,
            "characterErrorRate": distance / len(expected_normalized),
        })
    anchor_results = {
        anchor_id: normalize(value) in normalize(pages[page_no])
        for anchor_id, (page_no, value) in anchors.items()
    }
    result = {
        "schemaVersion": "material-rag-ocr-result-v1",
        "profile": args.profile,
        "pageMetrics": page_metrics,
        "overallCharacterErrorRate": total_distance / total_characters,
        "anchorRecall": sum(anchor_results.values()) / len(anchor_results),
        "anchorResults": anchor_results,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
