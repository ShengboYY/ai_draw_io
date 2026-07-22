import importlib.util
import json
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("evaluate_drawio_generation_tasks.py")
SPEC = importlib.util.spec_from_file_location("generation_eval", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GenerationTaskEvaluatorTest(unittest.TestCase):
    def test_accepts_parseable_xml_with_required_labels_and_citations(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {"minVertices": 1, "minEdges": 0,
                "requiredLabels": ["A"]}, "citationAssertions": {"minimumCitations": 1,
                "mustCiteAnchors": ["anchor-a"]}}
        response = {"xml": "<mxGraphModel><mxCell vertex='1' value='A'/></mxGraphModel>",
                    "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v1", "page": 1}]}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}
        self.assertTrue(MODULE.evaluate(task, response, anchors)["completed"])

    def test_rejects_missing_citation_or_malformed_xml(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        result = MODULE.evaluate(task, {"xml": "<broken", "citations": []}, {})
        self.assertFalse(result["xmlParseable"])
        self.assertFalse(result["completed"])

    def test_accepts_object_shaped_citation_anchor(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        response = {"xml": "<mxGraphModel><mxCell vertex='1'/></mxGraphModel>",
                    "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v1", "page": 1}]}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}
        self.assertTrue(MODULE.evaluate(task, response, anchors)["citationAssertionsPassed"])

    def test_rejects_non_drawio_xml_and_wrong_citation_location(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}
        malformed = MODULE.evaluate(task, {"xml": "<not-drawio><mxCell vertex='1'/></not-drawio>",
                                            "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v1", "page": 1}]}, anchors)
        wrong_location = MODULE.evaluate(task, {"xml": "<mxGraphModel><mxCell vertex='1'/></mxGraphModel>",
                                                 "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v0", "page": 99}]}, anchors)
        self.assertFalse(malformed["xmlParseable"])
        self.assertFalse(wrong_location["citationAssertionsPassed"])
