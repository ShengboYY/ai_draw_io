"""Tests for authored OCR profiles."""

from __future__ import annotations

import unittest

from evaluate_ocr import (
    ADDITIONAL_FACTS,
    DRAWIO_SCAN_FACTS,
    DRAWIO_SCANNED_DOCUMENT,
    SCANNED_DOCUMENT,
    document_profile,
)


class EvaluateOcrProfileTest(unittest.TestCase):

    def test_rail_and_drawio_profiles_include_all_pages_anchors_and_footers(self) -> None:
        rail_pages, rail_anchors = document_profile(SCANNED_DOCUMENT, ADDITIONAL_FACTS)
        drawio_pages, drawio_anchors = document_profile(
            DRAWIO_SCANNED_DOCUMENT, DRAWIO_SCAN_FACTS)

        self.assertEqual((6, 8), (len(rail_pages), len(rail_anchors)))
        self.assertEqual((6, 12), (len(drawio_pages), len(drawio_anchors)))
        self.assertTrue(all("Synthetic inspection scan | page" in page for page in rail_pages.values()))
        self.assertTrue(all("Synthetic inspection scan | page" in page for page in drawio_pages.values()))


if __name__ == "__main__":
    unittest.main()
