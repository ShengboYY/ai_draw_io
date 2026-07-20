#!/usr/bin/env python3
"""Generate deterministic multimodal RAG fixtures and anchor-level research cases."""

from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


ROOT = Path(__file__).resolve().parents[1]
UNICODE_FONT = Path("/Library/Fonts/Arial Unicode.ttf")
FALLBACK_FONT = Path("/System/Library/Fonts/Supplemental/Arial Unicode.ttf")
PAGE_WIDTH, PAGE_HEIGHT = A4
RANDOM = random.Random(20260720)


def font_path() -> Path:
    for candidate in (UNICODE_FONT, FALLBACK_FONT):
        if candidate.exists():
            return candidate
    raise RuntimeError("Arial Unicode font is required to generate bilingual fixtures")


def register_pdf_font() -> str:
    name = "ResearchUnicode"
    if name not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(name, str(font_path())))
    return name


def pil_font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(font_path()), size=size)


def draw_page_title(pdf: canvas.Canvas, title: str, subtitle: str, page_no: int, font: str) -> None:
    pdf.setFillColorRGB(0.08, 0.16, 0.29)
    pdf.setFont(font, 22)
    pdf.drawString(48, PAGE_HEIGHT - 58, title)
    pdf.setFillColorRGB(0.35, 0.40, 0.48)
    pdf.setFont(font, 9)
    pdf.drawString(48, PAGE_HEIGHT - 76, subtitle)
    pdf.drawRightString(PAGE_WIDTH - 48, 30, f"Research fixture - page {page_no}")


def draw_lines(pdf: canvas.Canvas, lines: list[str], x: float, y: float,
               font: str, size: int = 12, leading: int = 20) -> float:
    pdf.setFillColorRGB(0.08, 0.10, 0.14)
    pdf.setFont(font, size)
    for line in lines:
        pdf.drawString(x, y, line)
        y -= leading
    return y


def arrow(draw: ImageDraw.ImageDraw, start: tuple[int, int], end: tuple[int, int],
          color: str, width: int = 7, dashed: bool = False) -> None:
    x1, y1 = start
    x2, y2 = end
    if dashed:
        segments = 12
        for index in range(0, segments, 2):
            a = index / segments
            b = min(1.0, (index + 1) / segments)
            draw.line((x1 + (x2 - x1) * a, y1 + (y2 - y1) * a,
                       x1 + (x2 - x1) * b, y1 + (y2 - y1) * b), fill=color, width=width)
    else:
        draw.line((x1, y1, x2, y2), fill=color, width=width)
    angle = math.atan2(y2 - y1, x2 - x1)
    length = 22
    left = (x2 - length * math.cos(angle - 0.55), y2 - length * math.sin(angle - 0.55))
    right = (x2 - length * math.cos(angle + 0.55), y2 - length * math.sin(angle + 0.55))
    draw.polygon([(x2, y2), left, right], fill=color)


def generate_risk_flow(path: Path) -> None:
    image = Image.new("RGB", (1600, 900), "#f7f9fc")
    draw = ImageDraw.Draw(image)
    title = pil_font(50)
    label = pil_font(34)
    small = pil_font(26)
    draw.text((80, 55), "Risk Escalation Flow RF-204", font=title, fill="#14213d")
    boxes = {
        "ASSESS": (80, 300, 380, 460),
        "AUTO APPROVE": (620, 130, 1030, 290),
        "HUMAN REVIEW": (620, 520, 1030, 680),
        "RELEASE": (1220, 300, 1510, 460),
    }
    for text, bounds in boxes.items():
        draw.rounded_rectangle(bounds, radius=28, fill="white", outline="#2356a8", width=6)
        text_box = draw.textbbox((0, 0), text, font=label)
        width = text_box[2] - text_box[0]
        height = text_box[3] - text_box[1]
        draw.text(((bounds[0] + bounds[2] - width) / 2,
                   (bounds[1] + bounds[3] - height) / 2 - 5), text, font=label, fill="#14213d")
    arrow(draw, (380, 350), (620, 210), "#197645")
    arrow(draw, (380, 410), (620, 600), "#b3261e")
    arrow(draw, (1030, 210), (1220, 350), "#197645")
    arrow(draw, (1030, 600), (1220, 410), "#197645")
    arrow(draw, (820, 520), (360, 460), "#d97706", dashed=True)
    draw.text((420, 220), "score < 85", font=small, fill="#197645")
    draw.text((430, 515), "score >= 85", font=small, fill="#b3261e")
    draw.text((500, 760), "Dashed amber arrow: rejected review returns to ASSESS", font=small, fill="#7c4a03")
    image.save(path, optimize=True)


def generate_capacity_table(path: Path) -> None:
    image = Image.new("RGB", (1500, 900), "white")
    draw = ImageDraw.Draw(image)
    title = pil_font(50)
    header = pil_font(32)
    body = pil_font(30)
    draw.text((70, 55), "Service Capacity Matrix CAP-77", font=title, fill="#102a43")
    columns = [70, 390, 680, 980, 1430]
    rows = [170, 290, 410, 530, 650, 770]
    for x in columns:
        draw.line((x, rows[0], x, rows[-1]), fill="#334e68", width=4)
    for y in rows:
        draw.line((columns[0], y, columns[-1], y), fill="#334e68", width=4)
    headers = ["Service", "Monthly cap", "Alert", "Owner"]
    values = [
        ["Atlas", "120,000", "80%", "Platform Ops"],
        ["Beacon", "75,000", "70%", "Data Systems"],
        ["Comet", "48,500", "65%", "Analytics"],
        ["Delta", "210,000", "85%", "Core Services"],
    ]
    for index, value in enumerate(headers):
        draw.text((columns[index] + 18, 210), value, font=header, fill="#102a43")
    for row_index, row in enumerate(values):
        y = 330 + row_index * 120
        for column_index, value in enumerate(row):
            draw.text((columns[column_index] + 18, y), value, font=body, fill="#243b53")
    image.save(path, optimize=True)


def generate_scan_page(lines: list[str], output: Path, rotation: float,
                       contrast: float, noise_strength: float) -> None:
    image = Image.new("L", (1654, 2339), 247)
    draw = ImageDraw.Draw(image)
    title = pil_font(58)
    body = pil_font(38)
    y = 170
    for index, line in enumerate(lines):
        draw.text((135, y), line, font=title if index == 0 else body, fill=20)
        y += 105 if index == 0 else 78
    # Deterministic noise and mild degradation imitate a photographed/scanned office document.
    pixels = image.load()
    for _ in range(int(image.width * image.height * noise_strength)):
        x = RANDOM.randrange(image.width)
        y = RANDOM.randrange(image.height)
        pixels[x, y] = max(0, min(255, pixels[x, y] + RANDOM.randint(-42, 42)))
    image = ImageEnhance.Contrast(image).enhance(contrast)
    image = image.filter(ImageFilter.GaussianBlur(radius=0.45))
    image = image.rotate(rotation, resample=Image.Resampling.BICUBIC, expand=False, fillcolor=255)
    image.convert("RGB").save(output, quality=82, optimize=True)


def generate_scanned_pdf(path: Path, image_dir: Path) -> None:
    pages = [
        [
            "SCANNED OPERATIONS CARD",
            "Document code: OCR-AX9-771",
            "Emergency load threshold: 73%",
            "Escalation owner: Night Operations Lead",
            "Recovery window: 18 minutes",
            "The nearby value 37% is a training example, not the threshold.",
        ],
        [
            "扫描审批卡",
            "文件编号：OCR-ZH-882",
            "安全阈值：62%",
            "审批人：值班经理",
            "复核时间：每周三 14:30",
            "注意：26% 是演示数据，不是安全阈值。",
        ],
        [
            "SCANNED ZONE TABLE",
            "Zone | Limit | Owner",
            "North | 91 | Team Cedar",
            "South | 84 | Team Birch",
            "East | 76 | Team Maple",
            "West | 68 | Team Ash",
        ],
    ]
    rotations = [1.2, -1.7, 0.8]
    contrasts = [0.92, 0.82, 0.88]
    images: list[Path] = []
    for index, lines in enumerate(pages, start=1):
        output = image_dir / f"scanned-ops-page-{index}.jpg"
        generate_scan_page(lines, output, rotations[index - 1], contrasts[index - 1], 0.004)
        images.append(output)
    # Suppress creation timestamps and random document IDs so fixture hashes are reproducible.
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)
    for image_path in images:
        pdf.drawImage(ImageReader(str(image_path)), 0, 0, PAGE_WIDTH, PAGE_HEIGHT,
                      preserveAspectRatio=True, anchor="c")
        pdf.showPage()
    pdf.save()


def draw_capacity_pdf_table(pdf: canvas.Canvas, font: str) -> None:
    x_values = [48, 180, 315, 430, 548]
    y_top = PAGE_HEIGHT - 145
    row_height = 42
    rows = [
        ["Service", "SLA", "Budget", "Owner"],
        ["Atlas", "99.95%", "120,000 AUD", "Platform Ops"],
        ["Beacon", "99.90%", "75,000 AUD", "Data Systems"],
        ["Comet", "99.50%", "48,500 AUD", "Analytics"],
        ["Delta", "99.99%", "210,000 AUD", "Core Services"],
    ]
    pdf.setLineWidth(1)
    for row_index, row in enumerate(rows):
        y = y_top - row_index * row_height
        if row_index == 0:
            pdf.setFillColorRGB(0.88, 0.93, 0.98)
            pdf.rect(x_values[0], y - row_height + 8, x_values[-1] - x_values[0], row_height, fill=1)
        pdf.setFillColorRGB(0.08, 0.10, 0.14)
        pdf.setFont(font, 10)
        for column, value in enumerate(row):
            pdf.drawString(x_values[column] + 6, y - 18, value)
    for x in x_values:
        pdf.line(x, y_top + 8, x, y_top - len(rows) * row_height + 8)
    for index in range(len(rows) + 1):
        y = y_top - index * row_height + 8
        pdf.line(x_values[0], y, x_values[-1], y)


def generate_digital_pdf(path: Path, version: str, risk_flow: Path, capacity_table: Path) -> None:
    font = register_pdf_font()
    is_v2 = version == "v2"
    deployment_window = "03:00 UTC" if is_v2 else "02:00 UTC"
    retention_days = "45 days" if is_v2 else "30 days"
    risk_threshold = "90" if is_v2 else "85"
    approval_owner = "Reliability Council" if is_v2 else "Release Manager"
    # Suppress creation timestamps and random document IDs so fixture hashes are reproducible.
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)

    draw_page_title(pdf, f"Controlled Operations Guide {version.upper()}",
                    "Native bilingual PDF with exact and semantic evidence", 1, font)
    draw_lines(pdf, [
        "Release identifier: ORION-7",
        f"Standard deployment window: {deployment_window}",
        "Rollback begins after three consecutive health-check failures.",
        "Operational owner: Platform Operations.",
        "A single failure creates an alert but does not trigger rollback.",
    ], 58, PAGE_HEIGHT - 130, font, 13, 28)
    pdf.showPage()

    draw_page_title(pdf, "Service commitments", "Structured native-text table", 2, font)
    draw_capacity_pdf_table(pdf, font)
    pdf.setFont(font, 10)
    pdf.drawString(48, PAGE_HEIGHT - 390, "Table note: Delta has the highest SLA; Comet has the smallest budget.")
    pdf.showPage()

    draw_page_title(pdf, "Risk escalation", "Raster-only flow embedded in a native PDF", 3, font)
    pdf.drawImage(ImageReader(str(risk_flow)), 42, 150, PAGE_WIDTH - 84, 520,
                  preserveAspectRatio=True, anchor="c")
    pdf.setFont(font, 10)
    pdf.drawString(48, 120, "Figure 1. Risk escalation workflow. Visual relationships require pixel verification.")
    pdf.showPage()

    draw_page_title(pdf, "蓝鲸计划治理规则", "简体中文原生文本", 4, font)
    draw_lines(pdf, [
        "计划编号：CN-RISK-204",
        f"人工复核阈值：风险评分达到 {risk_threshold} 分。",
        "低于阈值的请求进入自动批准，高于或等于阈值的请求进入人工复核。",
        "复核被拒绝后返回评估阶段，不会直接进入发布阶段。",
        "每周三 14:30 进行风险规则校准。",
    ], 58, PAGE_HEIGHT - 135, font, 14, 30)
    pdf.showPage()

    draw_page_title(pdf, "Nearby concepts and hard negatives", "Similar terms with different meanings", 5, font)
    draw_lines(pdf, [
        "Sprint Review inspects the product outcome with stakeholders and considers future adaptations.",
        "Sprint Retrospective inspects team effectiveness and selects improvements to ways of working.",
        "Sprint Planning initiates the Sprint by defining why it is valuable, what can be done, and how.",
        "The code AG-RETRO-17 belongs only to the retrospective improvement register.",
        "The code AG-REVIEW-71 belongs only to the stakeholder review register.",
    ], 58, PAGE_HEIGHT - 135, font, 11, 31)
    pdf.showPage()

    draw_page_title(pdf, f"Version policy {version.upper()}", "Values intentionally differ between V1 and V2", 6, font)
    draw_lines(pdf, [
        f"Evidence retention period: {retention_days}",
        f"Final approval role: {approval_owner}",
        f"Policy version: {version.upper()}",
        "Existing diagrams remain pinned to the version they originally cited.",
        "New diagrams use the latest ready version unless the user explicitly selects another version.",
    ], 58, PAGE_HEIGHT - 135, font, 13, 29)
    pdf.showPage()

    draw_page_title(pdf, "Visual capacity matrix", "Raster table for cell-target evaluation", 7, font)
    pdf.drawImage(ImageReader(str(capacity_table)), 42, 150, PAGE_WIDTH - 84, 520,
                  preserveAspectRatio=True, anchor="c")
    pdf.setFont(font, 10)
    pdf.drawString(48, 120, "Figure 2. Capacity values are present in the raster table only.")
    pdf.showPage()

    draw_page_title(pdf, "No-answer control page", "Contains related words but omits the requested facts", 8, font)
    draw_lines(pdf, [
        "This guide discusses releases, reviews, capacity and risk.",
        "It intentionally contains no customer phone number, bank account, office address or password.",
        "A correct retrieval system must abstain when a requested fact is absent.",
    ], 58, PAGE_HEIGHT - 135, font, 12, 30)
    pdf.showPage()
    pdf.save()


def facts() -> list[dict]:
    return [
        {"anchorId": "orion-window-v1", "source": "controlled-guide-v1", "version": "v1", "page": 1,
         "modality": "text", "goldMatch": "02:00 UTC",
         "queries": ["What is the standard ORION-7 deployment window?", "ORION-7 默认几点部署？"]},
        {"anchorId": "orion-rollback", "source": "controlled-guide-v1", "version": "v1", "page": 1,
         "modality": "text", "goldMatch": "three consecutive health-check failures",
         "queries": ["How many consecutive health failures trigger rollback?", "什么条件会启动回滚？"]},
        {"anchorId": "atlas-sla", "source": "controlled-guide-v1", "version": "v1", "page": 2,
         "modality": "table", "goldMatch": "99.95%",
         "queries": ["What SLA is listed for Atlas?", "Atlas 的 SLA 是多少？"]},
        {"anchorId": "comet-budget", "source": "controlled-guide-v1", "version": "v1", "page": 2,
         "modality": "table", "goldMatch": "48,500 AUD",
         "queries": ["Which service has a 48,500 AUD budget?", "Comet 的预算是多少？"]},
        {"anchorId": "delta-owner", "source": "controlled-guide-v1", "version": "v1", "page": 2,
         "modality": "table", "goldMatch": "Core Services",
         "queries": ["Who owns the service with the highest SLA?", "最高 SLA 的服务由谁负责？"]},
        {"anchorId": "risk-high-path", "source": "controlled-guide-v1", "version": "v1", "page": 3,
         "modality": "visual", "goldMatch": "ASSESS->HUMAN REVIEW",
         "queries": ["Where does a score at or above 85 go?", "风险分数达到 85 后流向哪个阶段？"]},
        {"anchorId": "risk-feedback", "source": "controlled-guide-v1", "version": "v1", "page": 3,
         "modality": "visual", "goldMatch": "HUMAN REVIEW--dashed-->ASSESS",
         "queries": ["Where does the dashed rejection arrow return?", "虚线反馈箭头从人工复核返回哪里？"]},
        {"anchorId": "cn-risk-id", "source": "controlled-guide-v1", "version": "v1", "page": 4,
         "modality": "text", "goldMatch": "CN-RISK-204",
         "queries": ["蓝鲸计划的编号是什么？", "What is the identifier of the Blue Whale governance plan?"]},
        {"anchorId": "cn-risk-threshold-v1", "source": "controlled-guide-v1", "version": "v1", "page": 4,
         "modality": "text", "goldMatch": "85 分",
         "queries": ["V1 中人工复核阈值是多少？", "What score triggers human review in V1?"]},
        {"anchorId": "retro-code", "source": "controlled-guide-v1", "version": "v1", "page": 5,
         "modality": "text", "goldMatch": "AG-RETRO-17",
         "queries": ["Which register owns AG-RETRO-17?", "AG-RETRO-17 属于回顾还是评审？"]},
        {"anchorId": "retro-purpose", "source": "controlled-guide-v1", "version": "v1", "page": 5,
         "modality": "text", "goldMatch": "team effectiveness",
         "queries": ["Which Scrum event inspects team effectiveness?", "哪个事件用于改进团队工作方式？"]},
        {"anchorId": "review-purpose", "source": "controlled-guide-v1", "version": "v1", "page": 5,
         "modality": "text", "goldMatch": "product outcome with stakeholders",
         "queries": ["Which event inspects product outcomes with stakeholders?", "哪个 Scrum 事件与利益相关者检查成果？"]},
        {"anchorId": "retention-v1", "source": "controlled-guide-v1", "version": "v1", "page": 6,
         "modality": "text", "goldMatch": "30 days",
         "queries": ["What is the V1 evidence retention period?", "V1 的证据保留多少天？"]},
        {"anchorId": "retention-v2", "source": "controlled-guide-v2", "version": "v2", "page": 6,
         "modality": "text", "goldMatch": "45 days",
         "queries": ["What is the V2 evidence retention period?", "V2 的证据保留多少天？"]},
        {"anchorId": "approval-v1", "source": "controlled-guide-v1", "version": "v1", "page": 6,
         "modality": "text", "goldMatch": "Release Manager",
         "queries": ["Who gives final approval in V1?", "V1 的最终批准角色是谁？"]},
        {"anchorId": "approval-v2", "source": "controlled-guide-v2", "version": "v2", "page": 6,
         "modality": "text", "goldMatch": "Reliability Council",
         "queries": ["Who gives final approval in V2?", "V2 的最终批准角色是谁？"]},
        {"anchorId": "visual-beacon-cap", "source": "controlled-guide-v1", "version": "v1", "page": 7,
         "modality": "visual_table", "goldMatch": "Beacon|75,000|70%|Data Systems",
         "queries": ["What alert threshold is shown for Beacon?", "视觉表格中 Beacon 的告警阈值是多少？"]},
        {"anchorId": "visual-delta-cap", "source": "controlled-guide-v1", "version": "v1", "page": 7,
         "modality": "visual_table", "goldMatch": "Delta|210,000|85%|Core Services",
         "queries": ["Which visual-table row has an 85% alert?", "图片表格中 Delta 的月度上限是多少？"]},
        {"anchorId": "ocr-en-code", "source": "scanned-ops", "version": "v1", "page": 1,
         "modality": "ocr", "goldMatch": "OCR-AX9-771",
         "queries": ["What is the scanned operations card code?", "英文扫描卡的文件编号是什么？"]},
        {"anchorId": "ocr-en-threshold", "source": "scanned-ops", "version": "v1", "page": 1,
         "modality": "ocr", "goldMatch": "73%",
         "queries": ["What is the emergency load threshold?", "扫描卡中的紧急负载阈值是多少？"]},
        {"anchorId": "ocr-zh-code", "source": "scanned-ops", "version": "v1", "page": 2,
         "modality": "ocr", "goldMatch": "OCR-ZH-882",
         "queries": ["中文扫描审批卡的编号是什么？", "What identifier appears on the Chinese scanned approval card?"]},
        {"anchorId": "ocr-zh-threshold", "source": "scanned-ops", "version": "v1", "page": 2,
         "modality": "ocr", "goldMatch": "62%",
         "queries": ["扫描审批卡的安全阈值是多少？", "What is the safety threshold on the Chinese scan?"]},
        {"anchorId": "ocr-zone-east", "source": "scanned-ops", "version": "v1", "page": 3,
         "modality": "ocr_table", "goldMatch": "East|76|Team Maple",
         "queries": ["Who owns the East zone in the scanned table?", "扫描表格中 East 区域的限制值是多少？"]},
        {"anchorId": "whiteboard-feedback", "source": "whiteboard-review-feedback", "version": "v1", "page": 1,
         "modality": "image_visual", "goldMatch": "REVIEW--dashed-->INTAKE",
         "queries": ["Where does the whiteboard rework arrow return?", "白板上的返工虚线从 REVIEW 指向哪里？"]},
    ]


def write_ground_truth(output_root: Path) -> None:
    anchors = facts()
    (output_root / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-ground-truth-v1",
        "anchors": [{key: value for key, value in fact.items() if key != "queries"} for fact in anchors],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cases_path = output_root / "cases.jsonl"
    with cases_path.open("w", encoding="utf-8") as output:
        ordinal = 1
        for fact in anchors:
            for query in fact["queries"]:
                language = "zh" if any("\u4e00" <= char <= "\u9fff" for char in query) else "en"
                output.write(json.dumps({
                    "schemaVersion": "material-rag-research-case-v1",
                    "caseId": f"controlled-{ordinal:03d}",
                    "category": fact["modality"],
                    "language": language,
                    "query": query,
                    "allowedSourceVersions": [f"{fact['source']}:{fact['version']}"],
                    "goldAnchorIds": [fact["anchorId"]],
                    "expectedPage": fact["page"],
                    "answerable": True,
                }, ensure_ascii=False) + "\n")
                ordinal += 1
        no_answer_queries = [
            "What customer phone number is listed in the guide?",
            "文档中记录的银行账号是什么？",
            "What password is required for ORION-7?",
            "蓝鲸计划的办公地址在哪里？",
        ]
        for query in no_answer_queries:
            language = "zh" if any("\u4e00" <= char <= "\u9fff" for char in query) else "en"
            output.write(json.dumps({
                "schemaVersion": "material-rag-research-case-v1",
                "caseId": f"controlled-{ordinal:03d}",
                "category": "no_answer",
                "language": language,
                "query": query,
                "allowedSourceVersions": ["controlled-guide-v1:v1"],
                "goldAnchorIds": [],
                "expectedPage": None,
                "answerable": False,
            }, ensure_ascii=False) + "\n")
            ordinal += 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=ROOT / "fixtures" / "generated")
    args = parser.parse_args()
    output_root = args.output_root.resolve()
    pdf_dir = output_root / "pdfs"
    image_dir = output_root / "images"
    pdf_dir.mkdir(parents=True, exist_ok=True)
    image_dir.mkdir(parents=True, exist_ok=True)

    risk_flow = image_dir / "risk-escalation-flow.png"
    capacity_table = image_dir / "capacity-table.png"
    generate_risk_flow(risk_flow)
    generate_capacity_table(capacity_table)
    generate_digital_pdf(pdf_dir / "controlled-operations-guide-v1.pdf", "v1", risk_flow, capacity_table)
    generate_digital_pdf(pdf_dir / "controlled-operations-guide-v2.pdf", "v2", risk_flow, capacity_table)
    generate_scanned_pdf(pdf_dir / "scanned-operations-cards.pdf", image_dir)
    write_ground_truth(output_root)
    print(f"generated material RAG fixtures under {output_root}")


if __name__ == "__main__":
    main()
