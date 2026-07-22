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
        task = {"taskId": "x", "xmlAssertions": {"minVertices": 1, "minEdges": 0,
                "requiredLabels": ["A"]}, "citationAssertions": {"minimumCitations": 1,
                "mustCiteAnchors": ["anchor-a"]}}
        response = {"xml": "<mxGraphModel><mxCell vertex='1' value='A'/></mxGraphModel>",
                    "citations": ["anchor-a"]}
        self.assertTrue(MODULE.evaluate(task, response)["completed"])

    def test_rejects_missing_citation_or_malformed_xml(self):
        task = {"taskId": "x", "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        result = MODULE.evaluate(task, {"xml": "<broken", "citations": []})
        self.assertFalse(result["xmlParseable"])
        self.assertFalse(result["completed"])
