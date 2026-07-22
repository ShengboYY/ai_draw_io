#!/usr/bin/env python3
"""Generate deterministic multimodal RAG fixtures and anchor-level research cases."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import re
from functools import cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Table, TableStyle

from realistic_corpus_specs import (
    ADDITIONAL_FACTS,
    ADDITIONAL_MULTI_CASES,
    ADDITIONAL_NO_ANSWER_CASES,
    DIGITAL_DOCUMENTS,
    SCANNED_DOCUMENT,
)
from drawio_agent_corpus_specs import (
    DRAWIO_DIGITAL_DOCUMENTS,
    DRAWIO_FACTS,
    DRAWIO_MULTI_CASES,
    DRAWIO_NO_ANSWER_CASES,
    DRAWIO_SCAN_FACTS,
    DRAWIO_SCANNED_DOCUMENT,
)
from scenario_corpus_specs import (
    SCENARIO_DIGITAL_DOCUMENTS,
    SCENARIO_FACTS,
    SCENARIO_MULTI_CASES,
    SCENARIO_NO_ANSWER_CASES,
    SUPPLEMENT_FACTS,
    SUPPLEMENT_MULTI_CASES,
    SUPPLEMENT_NO_ANSWER_CASES,
)
from guard_corpus_specs import (
    GUARD_DIGITAL_DOCUMENTS,
    GUARD_FACTS,
    GUARD_LATE_CASES,
    GUARD_NO_ANSWER_CASES,
)
from expansion_corpus_specs import (
    EXPANSION_DIGITAL_DOCUMENTS,
    EXPANSION_FACTS,
    EXPANSION_MULTI_CASES,
    EXPANSION_NO_ANSWER_CASES,
    EXPANSION_SCANNED_DOCUMENTS,
    EXPANSION_SCAN_FACTS,
)


ROOT = Path(__file__).resolve().parents[1]
GENERATION_CONFIG = json.loads(
    (ROOT / "fixtures" / "generation-config.json").read_text(encoding="utf-8")
)
UNICODE_FONT = Path("/Library/Fonts/Arial Unicode.ttf")
FALLBACK_FONT = Path("/System/Library/Fonts/Supplemental/Arial Unicode.ttf")
FONT_SHA256 = GENERATION_CONFIG["fixtureFontSha256"]
PAGE_WIDTH, PAGE_HEIGHT = A4
RANDOM = random.Random(GENERATION_CONFIG["randomSeed"])
ALL_DIGITAL_DOCUMENTS = [*DIGITAL_DOCUMENTS, *DRAWIO_DIGITAL_DOCUMENTS, *SCENARIO_DIGITAL_DOCUMENTS,
                         *GUARD_DIGITAL_DOCUMENTS, *EXPANSION_DIGITAL_DOCUMENTS]
ALL_SCANNED_DOCUMENTS = [SCANNED_DOCUMENT, DRAWIO_SCANNED_DOCUMENT, *EXPANSION_SCANNED_DOCUMENTS]
OUTAGE_CHART_VALUES = [
    ("Docklands North", 16),
    ("River Berth", 12),
    ("Cold Store", 19),
    ("Transit Loop", 8),
]


@cache
def font_path() -> Path:
    """Resolve the pinned bilingual font and reject silent host-font drift."""
    configured = os.environ.get("MATERIAL_RAG_FONT_PATH")
    candidates = ([Path(configured)] if configured else []) + [UNICODE_FONT, FALLBACK_FONT]
    expected_sha = FONT_SHA256
    for candidate in candidates:
        if not candidate.exists():
            continue
        actual_sha = hashlib.sha256(candidate.read_bytes()).hexdigest()
        if actual_sha != expected_sha:
            raise RuntimeError(
                f"Fixture font hash mismatch for {candidate}: expected {expected_sha}, got {actual_sha}"
            )
        return candidate
    raise RuntimeError(
        "Pinned Arial Unicode font is required; set MATERIAL_RAG_FONT_PATH to a file with "
        f"SHA-256 {expected_sha}"
    )


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


def draw_spec_page_title(pdf: canvas.Canvas, document: dict, page: dict,
                         page_no: int, font: str) -> float:
    """Render visibly different headers for paper, field-memo and standard document families."""
    template = document.get("template", "standard")
    if template == "paper":
        pdf.setFillColorRGB(0.10, 0.10, 0.10)
        pdf.setFont(font, 18)
        pdf.drawCentredString(PAGE_WIDTH / 2, PAGE_HEIGHT - 54, page["title"])
        pdf.setFont(font, 8.5)
        pdf.setFillColorRGB(0.35, 0.35, 0.35)
        pdf.drawCentredString(PAGE_WIDTH / 2, PAGE_HEIGHT - 72, page["subtitle"])
        pdf.setStrokeColorRGB(0.25, 0.25, 0.25)
        pdf.line(56, PAGE_HEIGHT - 84, PAGE_WIDTH - 56, PAGE_HEIGHT - 84)
        footer_label = document.get("footerLabel", "Forest Evidence Working Paper")
        pdf.drawCentredString(PAGE_WIDTH / 2, 28, f"{footer_label} | {page_no}")
        return PAGE_HEIGHT - 102
    if template == "field_memo":
        pdf.setFillColorRGB(0.12, 0.27, 0.24)
        pdf.rect(0, PAGE_HEIGHT - 96, 18, 96, fill=1, stroke=0)
        pdf.setFillColorRGB(0.12, 0.18, 0.17)
        pdf.setFont(font, 9)
        pdf.drawString(42, PAGE_HEIGHT - 34,
                       document.get("headerLabel", "COLLECTION CARE / CONDITION MEMO"))
        pdf.setFont(font, 19)
        pdf.drawString(42, PAGE_HEIGHT - 59, page["title"])
        pdf.setFillColorRGB(0.38, 0.43, 0.42)
        pdf.setFont(font, 8.5)
        pdf.drawString(42, PAGE_HEIGHT - 78, page["subtitle"])
        footer_label = document.get("footerLabel", "ACM fixture")
        pdf.drawRightString(PAGE_WIDTH - 42, 28, f"{footer_label} | leaf {page_no}")
        return PAGE_HEIGHT - 112
    draw_page_title(pdf, page["title"], page["subtitle"], page_no, font)
    return PAGE_HEIGHT - 105


def draw_lines(pdf: canvas.Canvas, lines: list[str], x: float, y: float,
               font: str, size: int = 12, leading: int = 20) -> float:
    pdf.setFillColorRGB(0.08, 0.10, 0.14)
    pdf.setFont(font, size)
    for line in lines:
        pdf.drawString(x, y, line)
        y -= leading
    return y


def draw_article_sections(pdf: canvas.Canvas, sections: list[tuple[str, str]], y: float,
                          font: str, width: float = PAGE_WIDTH - 96, x: float = 48,
                          body_size: float = 9.4, body_leading: float = 13.2) -> float:
    """Draw dense report prose while keeping each authored page deterministic."""
    heading_style = ParagraphStyle(
        "article-heading", fontName=font, fontSize=body_size + 2.6, leading=body_leading + 1.8,
        textColor=colors.HexColor("#17365D"), spaceAfter=5,
    )
    body_style = ParagraphStyle(
        "article-body", fontName=font, fontSize=body_size, leading=body_leading,
        textColor=colors.HexColor("#20242B"), alignment=4, spaceAfter=8,
    )
    for heading, body in sections:
        heading_paragraph = Paragraph(heading, heading_style)
        _, heading_height = heading_paragraph.wrap(width, y - 48)
        heading_paragraph.drawOn(pdf, x, y - heading_height)
        y -= heading_height + 4
        # CJK justification inserts distracting gaps between short glyph runs, so use native wrapping.
        effective_body_style = body_style
        if any("\u4e00" <= character <= "\u9fff" for character in body):
            effective_body_style = ParagraphStyle(
                "article-body-cjk", parent=body_style, alignment=0, wordWrap="CJK",
            )
        body_paragraph = Paragraph(body, effective_body_style)
        _, body_height = body_paragraph.wrap(width, y - 48)
        body_paragraph.drawOn(pdf, x, y - body_height)
        y -= body_height + 9
    if y < 50:
        raise RuntimeError("Article fixture page overflowed; shorten the authored sections")
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


def generate_outage_chart(path: Path) -> None:
    """Create a raster-only chart whose values are absent from the PDF text layer."""
    image = Image.new("RGB", (1500, 900), "white")
    draw = ImageDraw.Draw(image)
    title = pil_font(48)
    label = pil_font(30)
    value_font = pil_font(28)
    draw.text((70, 50), "Average interruption minutes after upgrade", font=title, fill="#102a43")
    palette = ["#3572b0", "#2f855a", "#d97706", "#7c3aed"]
    values = [(label, minutes, palette[index])
              for index, (label, minutes) in enumerate(OUTAGE_CHART_VALUES)]
    x_start = 310
    y_start = 190
    scale = 58
    for index, (zone, minutes, color) in enumerate(values):
        y = y_start + index * 155
        draw.text((70, y + 20), zone, font=label, fill="#243b53")
        draw.rounded_rectangle((x_start, y, x_start + minutes * scale, y + 78),
                               radius=16, fill=color)
        draw.text((x_start + minutes * scale + 24, y + 18), f"{minutes} min",
                  font=value_font, fill="#102a43")
    draw.line((x_start, 155, x_start, 810), fill="#486581", width=4)
    draw.text((70, 830), "Source: validated incident logs, April-September 2026",
              font=value_font, fill="#52606d")
    image.save(path, optimize=True)


def generate_spec_bar_chart(path: Path, chart: dict) -> None:
    """Generate a document-specific raster chart without leaking values into the PDF text layer."""
    image = Image.new("RGB", (1500, 900), "white")
    draw = ImageDraw.Draw(image)
    draw.text((70, 50), chart["title"], font=pil_font(45), fill="#102a43")
    palette = ["#3572b0", "#2f855a", "#d97706", "#7c3aed", "#b83280"]
    maximum = max(value for _, value in chart["values"])
    scale = 900 / maximum
    for index, (label, value) in enumerate(chart["values"]):
        y = 180 + index * 145
        draw.text((70, y + 18), label, font=pil_font(29), fill="#243b53")
        draw.rounded_rectangle((380, y, 380 + value * scale, y + 72), radius=15,
                               fill=palette[index % len(palette)])
        draw.text((400 + value * scale, y + 16), f"{value}{chart['valueSuffix']}",
                  font=pil_font(27), fill="#102a43")
    draw.line((380, 145, 380, 800), fill="#486581", width=4)
    image.save(path, optimize=True)


def generate_spec_diagram(path: Path, diagram: dict) -> None:
    """Generate a simple process diagram whose node relationships require visual inspection."""
    image = Image.new("RGB", (1700, 900), "#f7f9fc")
    draw = ImageDraw.Draw(image)
    draw.text((70, 55), diagram["title"], font=pil_font(46), fill="#14213d")
    nodes = diagram["nodes"]
    centers = []
    box_width = 300
    for index, node in enumerate(nodes):
        x = 55 + index * 410
        bounds = (x, 330, x + box_width, 500)
        centers.append((x + box_width // 2, 415))
        draw.rounded_rectangle(bounds, radius=24, fill="white", outline="#2356a8", width=6)
        text_box = draw.textbbox((0, 0), node, font=pil_font(29))
        draw.text((x + (box_width - (text_box[2] - text_box[0])) / 2,
                   400 - (text_box[3] - text_box[1]) / 2), node,
                  font=pil_font(29), fill="#14213d")
    for index, label in enumerate(diagram["edgeLabels"]):
        start = (centers[index][0] + box_width // 2, centers[index][1])
        end = (centers[index + 1][0] - box_width // 2, centers[index + 1][1])
        arrow(draw, start, end, "#197645", width=6)
        draw.text((start[0] + 20, start[1] - 75), label, font=pil_font(24), fill="#197645")
    image.save(path, optimize=True)


def draw_spec_table(pdf: canvas.Canvas, table_spec: dict, font: str, y: float) -> float:
    """Render a native-text table from an authored document specification."""
    data = [table_spec["headers"], *table_spec["rows"]]
    table = Table(data, colWidths=table_spec["colWidths"], repeatRows=1)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font),
        ("FONTSIZE", (0, 0), (-1, -1), 8.2),
        ("LEADING", (0, 0), (-1, -1), 10),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#DCEAF7")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#102A43")),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#829AB1")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7FA")]),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    _, height = table.wrap(PAGE_WIDTH - 96, 500)
    table.drawOn(pdf, 48, y - height)
    return y - height - 12


def generate_spec_document(path: Path, document: dict, image_dir: Path) -> None:
    """Render a stable multi-page report while allowing each family to vary its page layout."""
    font = register_pdf_font()
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)
    body_size = document.get("bodyFontSize", 9.4)
    body_leading = document.get("bodyLeading", 13.2)
    for page_no, page in enumerate(document["pages"], start=1):
        content_top = draw_spec_page_title(pdf, document, page, page_no, font)
        if page.get("layout") == "columns":
            gap = 22
            width = (PAGE_WIDTH - 96 - gap) / 2
            split = (len(page["sections"]) + 1) // 2
            y = draw_article_sections(pdf, page["sections"][:split], content_top,
                                      font, width, 48, body_size, body_leading)
            draw_article_sections(pdf, page["sections"][split:], content_top,
                                  font, width, 48 + width + gap, body_size, body_leading)
        else:
            y = draw_article_sections(pdf, page["sections"], content_top, font,
                                      body_size=body_size, body_leading=body_leading)
        if "table" in page:
            y = draw_spec_table(pdf, page["table"], font, y)
            draw_article_sections(pdf, page.get("afterSections", []), y, font,
                                  body_size=body_size, body_leading=body_leading)
        if "diagram" in page:
            asset = image_dir / page["diagram"]["filename"]
            generate_spec_diagram(asset, page["diagram"])
            pdf.drawImage(ImageReader(str(asset)), 48, 90, PAGE_WIDTH - 96, 330,
                          preserveAspectRatio=True, anchor="c")
        if "chart" in page:
            asset = image_dir / page["chart"]["filename"]
            generate_spec_bar_chart(asset, page["chart"])
            pdf.drawImage(ImageReader(str(asset)), 48, 90, PAGE_WIDTH - 96, 330,
                          preserveAspectRatio=True, anchor="c")
        pdf.showPage()
    pdf.save()


def wrap_for_image(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont,
                   max_width: int) -> list[str]:
    """Wrap both spaced text and CJK text using measured glyph width."""
    units = text.split() if " " in text else list(text)
    separator = " " if " " in text else ""
    lines: list[str] = []
    current = ""
    for unit in units:
        candidate = unit if not current else current + separator + unit
        if draw.textlength(candidate, font=font) <= max_width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = unit
    if current:
        lines.append(current)
    return lines


def generate_scanned_article(path: Path, image_dir: Path, document: dict) -> None:
    """Create a multi-page image-only report with deterministic scan degradation."""
    page_images: list[Path] = []
    for page_no, page in enumerate(document["pages"], start=1):
        image = Image.new("L", (1654, 2339), 248)
        draw = ImageDraw.Draw(image)
        title_font = pil_font(52)
        subtitle_font = pil_font(28)
        heading_font = pil_font(34)
        body_font = pil_font(28)
        draw.text((115, 120), page["title"], font=title_font, fill=18)
        draw.text((115, 190), page["subtitle"], font=subtitle_font, fill=70)
        draw.line((115, 240, 1539, 240), fill=90, width=3)
        y = 290
        for heading, body in page["sections"]:
            draw.text((115, y), heading, font=heading_font, fill=25)
            y += 52
            for line in body.split("\n"):
                for wrapped in wrap_for_image(draw, line, body_font, 1424):
                    draw.text((115, y), wrapped, font=body_font, fill=32)
                    y += 42
                y += 10
            y += 24
        draw.line((115, 2240, 1539, 2240), fill=120, width=2)
        draw.text((115, 2265), f"Synthetic inspection scan | page {page_no}",
                  font=subtitle_font, fill=85)
        # Mild deterministic noise and rotation preserve readability while exercising OCR.
        pixels = image.load()
        for _ in range(int(image.width * image.height * 0.0025)):
            x = RANDOM.randrange(image.width)
            noise_y = RANDOM.randrange(image.height)
            pixels[x, noise_y] = max(0, min(255, pixels[x, noise_y] + RANDOM.randint(-38, 38)))
        image = image.filter(ImageFilter.GaussianBlur(radius=0.35))
        image = image.rotate((page_no % 3 - 1) * 0.7, resample=Image.Resampling.BICUBIC,
                             expand=False, fillcolor=255)
        image_prefix = document.get("imagePrefix", "rail-scan")
        image_path = image_dir / f"{image_prefix}-page-{page_no}.jpg"
        image.convert("RGB").save(image_path, quality=86, optimize=True)
        page_images.append(image_path)
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)
    for page_image in page_images:
        pdf.drawImage(ImageReader(str(page_image)), 0, 0, PAGE_WIDTH, PAGE_HEIGHT,
                      preserveAspectRatio=True, anchor="c")
        pdf.showPage()
    pdf.save()


def draw_reliability_table(pdf: canvas.Canvas, font: str, y: float) -> float:
    """Draw a dense native-text table similar to an operational report appendix."""
    data = [
        ["Site", "Peak MW", "Events", "Median restore", "Owner"],
        ["North Quay", "7.4", "6", "11 min", "Grid Control"],
        ["East Basin", "5.9", "4", "7 min", "Field Services"],
        ["South Pier", "8.1", "8", "14 min", "Grid Control"],
        ["West Yard", "6.7", "5", "9 min", "Asset Care"],
        ["Harbor Central", "9.3", "3", "6 min", "Network Ops"],
        ["Dry Dock", "4.8", "7", "13 min", "Field Services"],
        ["Container Gate", "7.9", "5", "10 min", "Asset Care"],
        ["Ferry Terminal", "5.2", "2", "5 min", "Network Ops"],
    ]
    table = Table(data, colWidths=[112, 62, 54, 105, 105], repeatRows=1)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font),
        ("FONTSIZE", (0, 0), (-1, -1), 8.2),
        ("LEADING", (0, 0), (-1, -1), 10),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#DCEAF7")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#102A43")),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#829AB1")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7FA")]),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    _, height = table.wrap(PAGE_WIDTH - 96, 500)
    table.drawOn(pdf, 48, y - height)
    return y - height - 12


def generate_realistic_report(path: Path, outage_chart: Path) -> None:
    """Generate a dense, article-style PDF without replacing the small regression fixtures."""
    font = register_pdf_font()
    pdf = canvas.Canvas(str(path), pagesize=A4, invariant=1)

    pages: list[tuple[str, str, list[tuple[str, str]]]] = [
        (
            "Harbor Grid Resilience Review",
            "HGR-2026-17 - synthetic long-form evaluation report",
            [
                ("Executive summary", "This review examines the Harbor West electricity resilience programme across 18 substations during the April to September 2026 observation period. The work combined incident logs, maintenance records, operator interviews and metering data so that conclusions did not depend on a single operational source. The programme was designed to reduce avoidable interruptions while preserving the conservative isolation rules used around cranes, ferry charging equipment and refrigerated container yards."),
                ("Headline finding", "The evidence indicates that reliability improved, although the effect was uneven between districts. Automated feeder checks reduced repeated dispatches in East Basin, while South Pier continued to experience restoration delays during night shifts. The report therefore recommends targeted staffing and relay calibration instead of a network-wide change to protection thresholds."),
                ("How to read this report", "Figures and tables use validated operational data. Narrative observations provide context but do not override a logged event. Where a metric changed between the baseline and the post-upgrade period, both pages must be read together. Nearby percentages and training examples are intentionally retained because they reflect the ambiguity found in real reports and create useful retrieval negatives."),
                ("Scope boundary", "The review covers operational resilience, energy use and governance. It does not contain customer contact details, payment credentials, private addresses or access passwords. Questions seeking those facts should be treated as unsupported rather than answered from background knowledge."),
                ("Recommendation sequence", "Management should first complete the label programme and night-shift staffing trial, then observe a second matched period before changing protection settings. This sequence preserves a stable technical baseline while addressing the operational weaknesses most strongly supported by the evidence. The review does not recommend purchasing a new control platform, because software availability was not a material contributor to the measured interruptions."),
            ],
        ),
        (
            "1. Programme context and method",
            "Long-form prose, references and exact identifiers",
            [
                ("Operating environment", "Harbor West is a mixed industrial precinct whose daily demand shifts with vessel arrivals, cold-storage loading and ferry charging. Unlike a residential feeder, its peak can move by several hours from one day to the next. Operators therefore evaluate both absolute demand and the sequence of equipment starts before changing a switching plan. The programme identifier used in maintenance systems is HBR-OPS-41; HBR-OPS-14 is an older training scenario and is not part of this review."),
                ("Evidence collection", "Analysts reconciled supervisory control records with field tickets and weekly planning notes. A timestamp was accepted only when two independent logs agreed within one minute. Meter readings were normalized for temperature and scheduled vessel activity. Interview statements were used to explain decisions, but every quantitative claim in the findings is tied to a table, incident record or approved calculation."),
                ("Review design", "The baseline covered eight complete weeks before the relay and scheduling changes. The comparison period used eight matched weeks after commissioning and excluded a planned port-wide shutdown. This design does not establish causality on its own, but it reduces obvious seasonal and workload differences. Sensitivity checks retained storm days and then removed them; the direction of the principal findings remained unchanged."),
                ("Terminology", "An interruption begins when service falls outside the validated operating envelope, not when the first warning appears. Restoration is recorded when stable supply has been maintained for five minutes. A dispatch is a physical field response, whereas a remote intervention is performed by the control room. These distinctions matter when matching questions to evidence."),
                ("Quality controls", "Two analysts independently reviewed unusual events and reconciled disagreements against the original log sequence. Calculations were reproduced from exported interval data, and every manually corrected record retained its earlier value in an audit column. Site names were normalized only after the original label had been preserved. These controls make the fixture useful for questions that distinguish an approved fact from a plausible nearby value."),
            ],
        ),
        (
            "2. Incident chronology",
            "Timeline narrative with nearby but non-equivalent times",
            [
                ("Initial warning", "At 04:11 on 18 June, the monitoring service raised a temperature warning for the South Pier feeder. The warning did not itself interrupt supply and did not authorize isolation. The duty controller compared the reading with the parallel sensor, checked the vessel schedule and requested a field confirmation because recent maintenance had changed the sensor housing."),
                ("Protective action", "At 04:17 the controller isolated feeder SP-3 after the parallel sensor confirmed a sustained rise. This is the authoritative isolation time. A radio note created at 04:19 describes the isolation retrospectively, while a maintenance ticket opened at 04:23 records the technician response. Those later timestamps are workflow events and must not be substituted for the operational action."),
                ("Restoration", "The inspection found a loose termination rather than a failed relay. After torque verification and a thermal scan, the feeder returned to service at 04:46. Stable supply was confirmed at 04:51 under the report's five-minute rule. The incident therefore contributed 34 interruption minutes to the operational series even though the elapsed time from first warning to stable confirmation was 40 minutes."),
                ("Lessons", "The review found that the controller followed the escalation procedure and that the early warning created useful preparation time. It also found that the radio and ticket systems described the same event with different timestamps. Retrieval tests should distinguish the requested event type before selecting a time."),
                ("Related events", "Two events from the same month were excluded from this chronology. A 03:52 warning at North Quay cleared without isolation, and a 05:08 ticket at West Yard concerned planned testing. They share equipment language with the South Pier incident but do not supply its action time, feeder identifier or restoration duration. Their inclusion here is intentional: realistic reports often discuss adjacent events that are topically similar but evidentially irrelevant."),
            ],
        ),
        (
            "3. Reliability profile by site",
            "Native-text table embedded in explanatory prose",
            [
                ("Interpretation", "Table 1 summarizes validated incidents after the upgrade. Peak demand is descriptive and is not an alert threshold. The event count includes both remote and field-restored interruptions, while median restore time uses the operational definition introduced in Section 1."),
            ],
        ),
        (
            "4. Governance and field observations",
            "Two-column policy discussion with role boundaries",
            [
                ("Decision rights", "Routine switching plans are approved by the Network Operations Lead. Emergency isolation remains with the duty controller because waiting for a committee could extend a hazardous condition. Changes to protection settings follow a different path: the Reliability Review Board examines the engineering case, and the Asset Director provides final authorization after independent safety review."),
                ("Meeting evidence", "The board met on 7 July with six voting members present. The quorum rule requires five voting members, so the decision was valid. Two observers from port operations attended but did not vote. Minutes refer to an earlier workshop attended by eight people; that attendance number is not the board quorum and should not be used to answer governance questions."),
                ("Field feedback", "Technicians reported that clearer device labels shortened handovers and reduced calls to the control room. They also noted that wet-weather access at South Pier remained slow. The feedback supports the staffing recommendation but does not establish the amount of reliability improvement, which comes from the incident analysis."),
                ("Control recommendation", "The report recommends retaining current trip thresholds, completing label replacement by 30 November 2026 and assigning an additional night-shift technician on high-traffic days. It explicitly rejects a proposal to raise thresholds simply to reduce alert volume."),
                ("Accountability trail", "Progress is reported monthly to the board secretary, but completion evidence is accepted by the Asset Assurance Manager. Procurement can confirm delivery of replacement labels; it cannot declare the operational action complete. This distinction prevents a delivery date from being mistaken for the approved installation deadline."),
            ],
        ),
        (
            "5. Baseline energy analysis",
            "First half of a cross-page comparison",
            [
                ("Measurement boundary", "Energy analysis covers auxiliary cooling and control equipment, not crane propulsion or tenant loads. Readings were collected at fifteen-minute intervals and aggregated only after clock drift and missing intervals were resolved. Three short gaps were filled using adjacent validated intervals; the imputed share was below one percent of the baseline total."),
                ("Baseline result", "During the matched pre-upgrade weeks, auxiliary systems used an average of 18.4 MWh per operating day. The median was 18.1 MWh and the highest single day reached 22.7 MWh during a heat event. The daily average, 18.4 MWh, is the baseline required for the programme comparison."),
                ("Nearby measures", "A separate facilities dashboard reports 17.6 MWh for administrative buildings. That number uses a different meter boundary and must not be combined with the harbor auxiliary series. The training worksheet also contains an illustrative 20 percent saving, but it is not an observed result."),
                ("Uncertainty", "Meter calibration uncertainty is estimated at plus or minus 0.3 MWh per day. Because the post-upgrade change exceeds this band, the review treats the direction of change as credible while avoiding claims of precision beyond one decimal place."),
                ("Baseline distribution", "Weekday averages were more stable than weekend averages because vessel arrivals concentrated auxiliary demand into predictable windows. No single site accounted for more than one third of the baseline series. Removing the highest-demand day reduced the average slightly but did not change the value selected for the matched comparison, which was calculated under the frozen analysis protocol."),
            ],
        ),
        (
            "6. Post-upgrade energy outcome",
            "Second half of a cross-page comparison",
            [
                ("Observed outcome", "Across the matched post-upgrade weeks, auxiliary systems used an average of 15.1 MWh per operating day. The reduction was concentrated in overnight cooling cycles and did not coincide with an increase in temperature alarms. This page must be combined with the 18.4 MWh baseline on the preceding page to calculate the absolute programme change."),
                ("Derived comparison", "The difference between the two validated daily averages is 3.3 MWh per operating day, equivalent to a reduction of approximately 17.9 percent relative to baseline. The report uses the unrounded values for internal calculations and publishes the one-decimal figures so readers can reproduce the stated comparison."),
                ("Alternative explanations", "Weather normalization accounts for part of the raw reduction, while changed vessel activity accounts for little of it. The review cannot exclude all behavioral effects because technicians knew the equipment was being monitored. Even so, sensitivity tests produced reductions between 15.8 and 18.6 percent."),
                ("Decision", "The programme should continue for another six months with the same meter boundary. Expanding the scope before that point would make the time series difficult to compare and would weaken the audit trail."),
                ("Monitoring plan", "The next review will report both the matched daily average and the full-period total so that operational growth is visible without changing the comparison definition. Analysts will also track temperature alarms, manual overrides and deferred maintenance. A reduction in energy use will not be considered successful if it coincides with weaker cooling performance or delayed safety action."),
            ],
        ),
        (
            "7. Interruption results by district",
            "Raster-only figure with surrounding narrative",
            [
                ("Figure guidance", "Figure 2 reports average interruption minutes after the upgrade for four districts. The chart values exist only in the raster image; this paragraph deliberately does not repeat them. A correct visual answer must inspect the bar length or printed value and keep the district-to-value relationship intact."),
            ],
        ),
        (
            "8. 中文现场复核记录",
            "中文长段落、近义概念与精确事实",
            [
                ("复核范围", "蓝港现场复核覆盖东港池与南码头两个区域，重点检查夜班交接、设备标签和告警确认流程。复核人员将控制室日志、现场工单和班组记录逐项核对，只有时间、设备编号和处置动作能够相互印证时，才把该记录纳入正式统计。访谈内容用于解释操作背景，但不能单独作为数值结论。"),
                ("关键要求", "中文作业补充规程的编号是 CN-HARBOR-62。规程要求当冷却回路连续三次超过 74 摄氏度时启动人工复核；单次短暂告警只记录观察，不立即改变运行状态。培训材料中的 47 摄氏度是演示值，不是触发阈值。"),
                ("交接发现", "抽查显示，多数班组能够说明告警确认与设备隔离的区别，但两个夜班记录把首次告警时间误写成处置时间。项目组因此要求交接表分别保留告警、确认、隔离和恢复四个字段，避免后续报告把不同事件混为一谈。"),
                ("改进期限", "设备标签更换应在 2026 年 11 月 30 日前完成，责任团队为现场服务组。这个期限与英文治理章节一致；会议纪要中出现的 10 月 30 日只是供应商提供标签样稿的日期，不能作为最终完成期限。"),
                ("后续验证", "完成标签更换后，质量人员将抽取白班和夜班记录，检查四类时间字段是否齐全，并确认现场编号与控制室编号能够对应。抽查通过不代表可以修改保护阈值；任何阈值调整仍需按照英文治理章节规定的审批路径执行。"),
            ],
        ),
        (
            "9. Version notes, limitations and negative evidence",
            "Conflicting values are labelled rather than silently removed",
            [
                ("Version statement", "This document is version 1.0, approved on 12 October 2026. A draft circulated in August proposed a 72 degree cooling threshold, but that proposal was rejected. The approved Chinese supplement sets the threshold at 74 degrees after three consecutive exceedances. Systems answering from the approved version must not revive the draft value."),
                ("Known limitations", "The observation period is too short to estimate rare major failures, and no conclusion is drawn about equipment outside the defined meter boundary. Ferry demand changed during two weeks, although the matched analysis reduces its influence. Results should not be generalized to residential networks without further evidence."),
                ("Unsupported requests", "The report contains no employee telephone numbers, customer account numbers, passwords, private addresses or banking information. It also does not name an insurance provider. A related topic or plausible-looking identifier is not evidence that one of these facts exists."),
                ("Archival note", "All source logs used for this synthetic fixture are represented by deterministic generated content. The document is designed for repeatable evaluation and contains no production data or real user identifiers."),
                ("Citation guidance", "A supported answer should cite the page containing the requested operational fact, not merely this limitations page. When a question compares baseline and post-upgrade performance, both analysis pages are required even though this section summarizes the document's status. Draft values may be mentioned only when the answer clearly identifies them as rejected and cites the approved replacement."),
            ],
        ),
    ]

    for page_no, (title, subtitle, sections) in enumerate(pages, start=1):
        draw_page_title(pdf, title, subtitle, page_no, font)
        if page_no == 5:
            column_gap = 22
            column_width = (PAGE_WIDTH - 96 - column_gap) / 2
            y = draw_article_sections(pdf, sections[:2], PAGE_HEIGHT - 105, font,
                                      column_width, 48)
            draw_article_sections(pdf, sections[2:], PAGE_HEIGHT - 105, font,
                                  column_width, 48 + column_width + column_gap)
        else:
            y = draw_article_sections(pdf, sections, PAGE_HEIGHT - 105, font)
        if page_no == 4:
            y = draw_reliability_table(pdf, font, y)
            draw_article_sections(pdf, [
                ("Reading the table", "South Pier has the highest event count and the longest median restore time among the listed sites. Harbor Central has the greatest peak demand but only three validated events, so peak demand alone does not explain incident frequency."),
            ], y, font)
        if page_no == 8:
            pdf.drawImage(ImageReader(str(outage_chart)), 55, 105, PAGE_WIDTH - 110, 300,
                          preserveAspectRatio=True, anchor="c")
            pdf.setFont(font, 8.5)
            pdf.drawString(55, 88, "Figure 2. Average interruption minutes after upgrade. Values are raster-only.")
        pdf.showPage()
    pdf.save()


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
        {"anchorId": "hgr-program-id", "source": "realistic-harbor-report", "version": "v1", "page": 2,
         "modality": "text", "goldMatch": "HBR-OPS-41",
         "queries": ["What maintenance-system identifier belongs to the Harbor West programme?",
                     "Harbor West 项目在维护系统中的编号是什么？"]},
        {"anchorId": "hgr-isolation-time", "source": "realistic-harbor-report", "version": "v1", "page": 3,
         "modality": "text", "goldMatch": "At 04:17 the controller isolated feeder SP-3",
         "queries": ["When was feeder SP-3 actually isolated?", "SP-3 馈线实际在几点被隔离？"]},
        {"anchorId": "hgr-south-pier-restore", "source": "realistic-harbor-report", "version": "v1", "page": 4,
         "modality": "table", "goldMatch": "14 min",
         "queries": ["What median restore value is listed in the South Pier row?",
                     "South Pier 行列出的恢复时间中位数是多少？"]},
        {"anchorId": "hgr-quorum", "source": "realistic-harbor-report", "version": "v1", "page": 5,
         "modality": "text", "goldMatch": "quorum rule requires five voting members",
         "queries": ["How many voting members are required for board quorum?", "委员会法定人数需要几名投票成员？"]},
        {"anchorId": "hgr-baseline-energy", "source": "realistic-harbor-report", "version": "v1", "page": 6,
         "modality": "text", "goldMatch": "average of 18.4 MWh per operating day",
         "queries": ["What was average daily auxiliary energy use before the upgrade?",
                     "升级前辅助系统的日均能耗是多少？"]},
        {"anchorId": "hgr-post-energy", "source": "realistic-harbor-report", "version": "v1", "page": 7,
         "modality": "text", "goldMatch": "average of 15.1 MWh per operating day",
         "queries": ["What was average daily auxiliary energy use after the upgrade?",
                     "升级后辅助系统的日均能耗是多少？"]},
        {"anchorId": "hgr-derived-reduction", "source": "realistic-harbor-report", "version": "v1", "page": 7,
         "modality": "text", "goldMatch": "3.3 MWh per operating day",
         "queries": ["What absolute daily energy reduction does the report publish?",
                     "报告公布的日均能耗绝对降幅是多少？"]},
        {"anchorId": "hgr-river-berth-chart", "source": "realistic-harbor-report", "version": "v1", "page": 8,
         "modality": "visual_chart", "goldMatch": "River Berth|12 min",
         "queries": ["How many average interruption minutes does the chart show for River Berth?",
                     "图表显示 River Berth 的平均中断时间是多少？"]},
        {"anchorId": "hgr-cn-code", "source": "realistic-harbor-report", "version": "v1", "page": 9,
         "modality": "text", "goldMatch": "CN-HARBOR-62",
         "queries": ["中文作业补充规程的编号是什么？", "What is the Chinese operating supplement identifier?"]},
        {"anchorId": "hgr-cn-threshold", "source": "realistic-harbor-report", "version": "v1", "page": 9,
         "modality": "text", "goldMatch": "连续三次超过 74 摄氏度",
         "queries": ["冷却回路触发人工复核的条件是什么？",
                     "What cooling-loop condition triggers manual review?"]},
        {"anchorId": "hgr-label-deadline", "source": "realistic-harbor-report", "version": "v1", "page": 9,
         "modality": "text", "goldMatch": "2026 年 11 月 30 日前完成",
         "queries": ["设备标签更换的最终期限是什么时候？",
                     "What is the final deadline for replacing equipment labels?"]},
        {"anchorId": "hgr-approved-version", "source": "realistic-harbor-report", "version": "v1", "page": 10,
         "modality": "text", "goldMatch": "version 1.0, approved on 12 October 2026",
         "queries": ["When was version 1.0 approved?", "报告 1.0 版何时获批？"]},
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
    ] + ADDITIONAL_FACTS + DRAWIO_FACTS + DRAWIO_SCAN_FACTS + SCENARIO_FACTS + SUPPLEMENT_FACTS \
        + GUARD_FACTS + EXPANSION_FACTS + EXPANSION_SCAN_FACTS


def generated_documents() -> list[dict]:
    """Describe family and split ownership before cases are emitted."""
    documents = [
        {"source": "controlled-guide-v1", "documentFamily": "controlled-operations-guide",
         "version": "v1", "split": "guard_regression", "language": "mixed", "role": "controlled",
         "filename": "controlled-operations-guide-v1.pdf"},
        {"source": "controlled-guide-v2", "documentFamily": "controlled-operations-guide",
         "version": "v2", "split": "guard_regression", "language": "mixed", "role": "controlled",
         "filename": "controlled-operations-guide-v2.pdf"},
        {"source": "scanned-ops", "documentFamily": "controlled-scanned-cards",
         "version": "v1", "split": "guard_visual_ocr", "language": "mixed", "role": "controlled",
         "filename": "scanned-operations-cards.pdf"},
        {"source": "whiteboard-review-feedback", "documentFamily": "whiteboard-review-feedback",
         "version": "v1", "split": "guard_visual_ocr", "language": "en", "role": "challenge",
         "filename": "whiteboard-review-feedback.png"},
        {"source": "realistic-harbor-report", "documentFamily": "harbor-grid-resilience",
         "version": "v1", "split": "development", "language": "mixed", "role": "realistic",
         "filename": "realistic-harbor-grid-report-v1.pdf"},
    ]
    documents.extend({
        "source": document["source"],
        "documentFamily": document["documentFamily"],
        "version": document["version"],
        "split": document["split"],
        "language": document["language"],
        "role": "realistic",
        "freezeStatus": "candidate" if document["split"] in {"validation", "holdout"} else "development",
        "filename": document["filename"],
    } for document in ALL_DIGITAL_DOCUMENTS)
    documents.extend({
        "source": document["source"],
        "documentFamily": document["documentFamily"],
        "version": document["version"],
        "split": document["split"],
        "language": document["language"],
        "role": "challenge" if document["split"].startswith("guard") else "realistic_scan",
        "freezeStatus": "guard" if document["split"].startswith("guard") else "development",
        "filename": document["filename"],
    } for document in ALL_SCANNED_DOCUMENTS)
    return documents


def source_metadata() -> dict[str, dict]:
    return {document["source"]: document for document in generated_documents()}


def normalize_authored_text(value: str) -> str:
    return re.sub(r"\s+", "", value).casefold()


def query_language(query: str) -> str:
    return "zh" if any("\u4e00" <= character <= "\u9fff" for character in query) else "en"


def language_target(query: str, document: dict) -> tuple[str, list[str]]:
    """Distinguish monolingual cases from queries crossing a single-language document."""
    query_language_value = query_language(query)
    cross_language = document["language"] in {"en", "zh"} \
        and query_language_value != document["language"]
    target = "crossLanguage" if cross_language else query_language_value
    tags = [f"queryLanguage:{query_language_value}", f"documentLanguage:{document['language']}"]
    if cross_language:
        tags.append("crossLanguage")
    return target, tags


def fact_primary_category(fact: dict, target_language: str) -> str:
    if fact.get("primaryCategory"):
        return fact["primaryCategory"]
    modality = fact["modality"]
    if modality.startswith("visual") or modality == "image_visual":
        return "visual"
    if modality.startswith("ocr"):
        return "ocr"
    if target_language == "crossLanguage":
        return "crossLanguage"
    return "exactLookup"


def validate_additional_specs() -> None:
    """Fail generation when authored facts drift away from their assigned pages."""
    documents = {document["source"]: document for document in ALL_DIGITAL_DOCUMENTS}
    documents.update({document["source"]: document for document in ALL_SCANNED_DOCUMENTS})
    for fact in [*ADDITIONAL_FACTS, *DRAWIO_FACTS, *DRAWIO_SCAN_FACTS, *SCENARIO_FACTS,
                 *SUPPLEMENT_FACTS, *GUARD_FACTS, *EXPANSION_FACTS, *EXPANSION_SCAN_FACTS]:
        document = documents[fact["source"]]
        page = document["pages"][fact["page"] - 1]
        native_parts = [page["title"], page["subtitle"]]
        for heading, body in [*page.get("sections", []), *page.get("afterSections", [])]:
            native_parts.extend([heading, body])
        if "table" in page:
            native_parts.extend(page["table"]["headers"])
            native_parts.extend(cell for row in page["table"]["rows"] for cell in row)
        page_text = normalize_authored_text(" ".join(native_parts))
        gold = normalize_authored_text(fact["goldMatch"])
        modality = fact["modality"]
        if modality.startswith("visual"):
            if gold in page_text:
                raise RuntimeError(f"Visual gold leaked into native page text: {fact['anchorId']}")
        elif gold not in page_text:
            raise RuntimeError(f"Gold text is absent from authored page: {fact['anchorId']}")

    special_visual_facts = {
        "controlled-guide-v1": {
            "ASSESS->HUMAN REVIEW",
            "HUMAN REVIEW--dashed-->ASSESS",
            "Beacon|75,000|70%|Data Systems",
            "Delta|210,000|85%|Core Services",
        },
        "realistic-harbor-report": {
            f"{label}|{minutes} min" for label, minutes in OUTAGE_CHART_VALUES
        },
        "whiteboard-review-feedback": {"REVIEW--dashed-->INTAKE"},
    }
    for fact in facts():
        if not fact["modality"].startswith("visual") and fact["modality"] != "image_visual":
            continue
        representations = set(special_visual_facts.get(fact["source"], set()))
        if fact["source"] in documents:
            page = documents[fact["source"]]["pages"][fact["page"] - 1]
            if "diagram" in page:
                nodes = page["diagram"]["nodes"]
                representations.update(
                    f"{nodes[index]}->{nodes[index + 1]}" for index in range(len(nodes) - 1)
                )
            if "chart" in page:
                suffix = page["chart"]["valueSuffix"]
                representations.update(
                    f"{label}|{value:g}{suffix}" for label, value in page["chart"]["values"]
                )
        normalized_representations = {normalize_authored_text(value) for value in representations}
        if normalize_authored_text(fact["goldMatch"]) not in normalized_representations:
            raise RuntimeError(f"Visual gold is absent from image configuration: {fact['anchorId']}")


SELECTION_PATH = ROOT / "fixtures" / "query-selection.json"


def load_drop_selection() -> set[str]:
    """Load the optional case-selection file that trims the corpus to V2 targets.

    The file lists stable case keys to drop. It lets exact category/language/split
    balancing be expressed as reviewable data instead of deleting authored queries,
    so every anchor stays available as ground truth even when a query is not used.
    """
    if SELECTION_PATH.exists():
        payload = json.loads(SELECTION_PATH.read_text(encoding="utf-8"))
        return set(payload.get("drop", []))
    return set()


def write_ground_truth(output_root: Path) -> None:
    anchors = facts()
    metadata = source_metadata()
    drop_selection = load_drop_selection()
    case_keys: dict[str, str] = {}
    (output_root / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-ground-truth-v1",
        "anchors": [{
            **{key: value for key, value in fact.items() if key != "queries"},
            "documentFamily": metadata[fact["source"]]["documentFamily"],
            "split": metadata[fact["source"]]["split"],
        } for fact in anchors],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_root / "corpus-manifest.json").write_text(json.dumps({
        "schemaVersion": "material-rag-generated-corpus-v1",
        "documents": generated_documents(),
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cases_path = output_root / "cases.jsonl"
    with cases_path.open("w", encoding="utf-8") as output:
        ordinal = 1
        for fact in anchors:
            document = metadata[fact["source"]]
            for query in fact["queries"]:
                case_key = f"{fact['anchorId']}::{query}"
                if case_key in drop_selection:
                    continue
                language, tags = language_target(query, document)
                case_record = {
                    "schemaVersion": "material-rag-research-case-v2",
                    "caseId": f"controlled-{ordinal:03d}",
                    "category": fact["modality"],
                    "language": language,
                    "queryLanguage": query_language(query),
                    "primaryCategory": fact_primary_category(fact, language),
                    "tags": [f"modality:{fact['modality']}", *tags],
                    "split": document["split"],
                    "documentFamily": document["documentFamily"],
                    "query": query,
                    "allowedSourceVersions": [f"{fact['source']}:{fact['version']}"],
                    "goldAnchorIds": [fact["anchorId"]],
                    "requiredEvidenceGroups": [{
                        "groupId": "answer",
                        "operator": "ANY",
                        "evidence": [{"anchorId": fact["anchorId"], "grade": 3, "minimumGrade": 3}],
                    }],
                    "expectedAnswer": fact.get("expectedAnswer", fact["goldMatch"]),
                    "abstentionCondition": None,
                    "expectedPage": fact["page"],
                    "answerable": True,
                }
                # Scenario categories carry executable conditions instead of relying on labels alone.
                if fact.get("evaluationContext"):
                    case_record["evaluationContext"] = fact["evaluationContext"]
                case_keys[case_record["caseId"]] = case_key
                output.write(json.dumps(case_record, ensure_ascii=False) + "\n")
                ordinal += 1
        # Each comparison keeps all required pages within one preassigned document family.
        multi_cases = [{
            "category": "multi_evidence",
            "split": "development",
            "sourceVersion": "realistic-harbor-report:v1",
            "anchorIds": ["hgr-baseline-energy", "hgr-post-energy"],
            "groupId": "before_after",
            "expectedPages": [6, 7],
            "expectedAnswer": "18.4 MWh per operating day before and 15.1 MWh after, a decrease of 3.3 MWh per operating day or about 17.9%.",
            "queries": [
                ("en", "What were average daily auxiliary energy use before and after the upgrade, "
                       "and how much did it fall?"),
                ("zh", "升级前后辅助系统的日均能耗分别是多少，降低了多少？"),
            ],
        }, *ADDITIONAL_MULTI_CASES, *DRAWIO_MULTI_CASES, *SCENARIO_MULTI_CASES,
            *SUPPLEMENT_MULTI_CASES, *EXPANSION_MULTI_CASES]
        for case in multi_cases:
            source = case["sourceVersion"].split(":", maxsplit=1)[0]
            for _, query in case["queries"]:
                case_key = f"multi:{case['sourceVersion']}:{case['groupId']}::{query}"
                if case_key in drop_selection:
                    continue
                language, tags = language_target(query, metadata[source])
                case_id = f"controlled-{ordinal:03d}"
                case_keys[case_id] = case_key
                output.write(json.dumps({
                    "schemaVersion": "material-rag-research-case-v2",
                    "caseId": case_id,
                    "category": case["category"],
                    "language": language,
                    "queryLanguage": query_language(query),
                    "primaryCategory": "multiEvidence",
                    "tags": ["multiEvidence", *tags],
                    "split": case["split"],
                    "documentFamily": metadata[source]["documentFamily"],
                    "query": query,
                    "allowedSourceVersions": [case["sourceVersion"]],
                    "goldAnchorIds": case["anchorIds"],
                    "requiredEvidenceGroups": [{
                        "groupId": case["groupId"],
                        "operator": "ALL_PARTS",
                        "evidence": [
                            {"anchorId": anchor_id, "grade": 2, "minimumGrade": 2}
                            for anchor_id in case["anchorIds"]
                        ],
                    }],
                    "expectedAnswer": case["expectedAnswer"],
                    "abstentionCondition": None,
                    "expectedPages": case["expectedPages"],
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
            case_key = f"noanswer:controlled-guide-v1:v1::{query}"
            if case_key in drop_selection:
                continue
            document = metadata["controlled-guide-v1"]
            language, tags = language_target(query, document)
            case_id = f"controlled-{ordinal:03d}"
            case_keys[case_id] = case_key
            output.write(json.dumps({
                "schemaVersion": "material-rag-research-case-v2",
                "caseId": case_id,
                "category": "no_answer",
                "language": language,
                "queryLanguage": query_language(query),
                "primaryCategory": "noAnswerAndMisleading",
                "tags": ["noAnswer", *tags],
                "split": "guard_regression",
                "documentFamily": "controlled-operations-guide",
                "query": query,
                "allowedSourceVersions": ["controlled-guide-v1:v1"],
                "goldAnchorIds": [],
                "requiredEvidenceGroups": [],
                "expectedAnswer": None,
                "abstentionCondition": "The requested fact is explicitly absent from the allowed source version.",
                "expectedPage": None,
                "answerable": False,
            }, ensure_ascii=False) + "\n")
            ordinal += 1
        for no_answer in [*ADDITIONAL_NO_ANSWER_CASES, *DRAWIO_NO_ANSWER_CASES, *SCENARIO_NO_ANSWER_CASES,
                          *SUPPLEMENT_NO_ANSWER_CASES, *GUARD_NO_ANSWER_CASES, *EXPANSION_NO_ANSWER_CASES]:
            source, version = no_answer["sourceVersion"].split(":", maxsplit=1)
            document = metadata[source]
            query = no_answer["query"]
            case_key = f"noanswer:{no_answer['sourceVersion']}::{query}"
            if case_key in drop_selection:
                continue
            language, tags = language_target(query, document)
            case_id = f"controlled-{ordinal:03d}"
            case_keys[case_id] = case_key
            output.write(json.dumps({
                "schemaVersion": "material-rag-research-case-v2",
                "caseId": case_id,
                "category": "no_answer",
                "language": language,
                "queryLanguage": query_language(query),
                "primaryCategory": "noAnswerAndMisleading",
                "tags": ["noAnswer", *tags],
                "split": document["split"],
                "documentFamily": document["documentFamily"],
                "query": query,
                "allowedSourceVersions": [f"{source}:{version}"],
                "goldAnchorIds": [],
                "requiredEvidenceGroups": [],
                "expectedAnswer": None,
                "abstentionCondition": "The requested fact is explicitly absent from the allowed source version.",
                "expectedPage": None,
                "answerable": False,
            }, ensure_ascii=False) + "\n")
            ordinal += 1

        # Guard growth is deliberately last: the reviewed core keeps its frozen ordinal IDs.
        anchor_by_id = {fact["anchorId"]: fact for fact in anchors}
        for late_case in GUARD_LATE_CASES:
            fact = anchor_by_id[late_case["anchorId"]]
            document = metadata[fact["source"]]
            query = late_case["query"]
            language, tags = language_target(query, document)
            case_id = f"controlled-{ordinal:03d}"
            case_key = f"guard:{fact['anchorId']}::{query}"
            case_keys[case_id] = case_key
            case_record = {
                "schemaVersion": "material-rag-research-case-v2",
                "caseId": case_id,
                "category": fact["modality"],
                "language": language,
                "queryLanguage": query_language(query),
                "primaryCategory": fact_primary_category(fact, language),
                "tags": [f"modality:{fact['modality']}", *tags],
                "split": document["split"],
                "documentFamily": document["documentFamily"],
                "query": query,
                "allowedSourceVersions": [f"{fact['source']}:{fact['version']}"],
                "goldAnchorIds": [fact["anchorId"]],
                "requiredEvidenceGroups": [{
                    "groupId": "answer",
                    "operator": "ANY",
                    "evidence": [{"anchorId": fact["anchorId"], "grade": 3, "minimumGrade": 3}],
                }],
                "expectedAnswer": fact.get("expectedAnswer", fact["goldMatch"]),
                "abstentionCondition": None,
                "expectedPage": fact["page"],
                "answerable": True,
            }
            if fact.get("evaluationContext"):
                case_record["evaluationContext"] = fact["evaluationContext"]
            output.write(json.dumps(case_record, ensure_ascii=False) + "\n")
            ordinal += 1
    (output_root / "case-keys.json").write_text(
        json.dumps(case_keys, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=ROOT / "fixtures" / "generated")
    args = parser.parse_args()
    output_root = args.output_root.resolve()
    pdf_dir = output_root / "pdfs"
    image_dir = output_root / "images"
    pdf_dir.mkdir(parents=True, exist_ok=True)
    image_dir.mkdir(parents=True, exist_ok=True)
    validate_additional_specs()

    risk_flow = image_dir / "risk-escalation-flow.png"
    capacity_table = image_dir / "capacity-table.png"
    outage_chart = image_dir / "harbor-outage-chart.png"
    generate_risk_flow(risk_flow)
    generate_capacity_table(capacity_table)
    generate_outage_chart(outage_chart)
    generate_digital_pdf(pdf_dir / "controlled-operations-guide-v1.pdf", "v1", risk_flow, capacity_table)
    generate_digital_pdf(pdf_dir / "controlled-operations-guide-v2.pdf", "v2", risk_flow, capacity_table)
    generate_realistic_report(pdf_dir / "realistic-harbor-grid-report-v1.pdf", outage_chart)
    for document in ALL_DIGITAL_DOCUMENTS:
        generate_spec_document(pdf_dir / document["filename"], document, image_dir)
    generate_scanned_pdf(pdf_dir / "scanned-operations-cards.pdf", image_dir)
    for document in ALL_SCANNED_DOCUMENTS:
        generate_scanned_article(pdf_dir / document["filename"], image_dir, document)
    write_ground_truth(output_root)
    print(f"generated material RAG fixtures under {output_root}")


if __name__ == "__main__":
    main()
