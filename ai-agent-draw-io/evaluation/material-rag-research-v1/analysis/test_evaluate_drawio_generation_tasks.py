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
        response = {"xml": "<mxGraphModel><mxCell vertex='1' parent='1' value='A'/></mxGraphModel>",
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
        response = {"xml": "<mxGraphModel><mxCell vertex='1' parent='1'/></mxGraphModel>",
                    "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v1", "page": 1}]}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}
        self.assertTrue(MODULE.evaluate(task, response, anchors)["requiredCitationContractPassed"])

    def test_rejects_non_drawio_xml_and_wrong_citation_location(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}
        malformed = MODULE.evaluate(task, {"xml": "<not-drawio><mxCell vertex='1'/></not-drawio>",
                                            "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v1", "page": 1}]}, anchors)
        wrong_location = MODULE.evaluate(task, {"xml": "<mxGraphModel><mxCell vertex='1' parent='1'/></mxGraphModel>",
                                                 "citations": [{"anchorId": "anchor-a", "sourceVersion": "source:v0", "page": 99}]}, anchors)
        self.assertFalse(malformed["xmlParseable"])
        self.assertFalse(wrong_location["requiredCitationContractPassed"])

    def test_accepts_required_label_inside_drawio_html_label(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {
                "minVertices": 1, "requiredLabels": ["Candidate retrieval"]},
                "citationAssertions": {"minimumCitations": 0, "mustCiteAnchors": []}}
        response = {"xml": "<mxGraphModel><mxCell vertex='1' parent='1' value='Candidate retrieval&lt;br&gt;&lt;b&gt;40&lt;/b&gt;'/></mxGraphModel>",
                    "citations": []}

        result = MODULE.evaluate(task, response, {})

        self.assertTrue(result["xmlAssertionsPassed"])

    def test_rejects_non_string_model_fields_and_v1_inside_v10(self):
        task = {"taskId": "x", "sourceVersion": "source:v1", "xmlAssertions": {
                "minVertices": 1, "requiredLabels": ["V1"]},
                "citationAssertions": {"minimumCitations": 1, "mustCiteAnchors": ["anchor-a"]}}
        anchors = {"anchor-a": {"source": "source", "version": "v1", "page": 1}}

        result = MODULE.evaluate(task, {"xml": None, "citations": [{
            "anchorId": ["anchor-a"], "sourceVersion": "source:v1", "page": 1,
        }]}, anchors)
        v10 = MODULE.evaluate(task, {"xml": "<mxGraphModel><mxCell vertex='1' parent='1' value='V10'/></mxGraphModel>",
                                     "citations": []}, anchors)

        self.assertFalse(result["xmlParseable"])
        self.assertFalse(result["requiredCitationContractPassed"])
        self.assertFalse(v10["xmlAssertionsPassed"])

    def test_requires_real_edit_preservation_and_requested_change(self):
        task = {"taskId": "edit", "sourceVersion": "source:v1",
                "xmlAssertions": {"minVertices": 2},
                "editAssertions": {
                    "preserveCellIds": ["fact", "protected"],
                    "preserveCellValues": {"protected": "Keep me"},
                    "requiredCellValues": {"fact": "New fact"},
                    "requiredCellAttributes": {"fact": {"evidenceSourceVersion": "source:v1"}},
                },
                "citationAssertions": {"minimumCitations": 0, "mustCiteAnchors": []}}
        valid = {"xml": "<mxGraphModel><root><mxCell id='fact' vertex='1' parent='1' value='New fact' evidenceSourceVersion='source:v1'/><mxCell id='protected' vertex='1' parent='1' value='Keep me'/></root></mxGraphModel>", "citations": []}
        destructive = {"xml": "<mxGraphModel><root><mxCell id='fact' vertex='1' parent='1' value='New fact' evidenceSourceVersion='source:v1'/></root></mxGraphModel>", "citations": []}

        self.assertTrue(MODULE.evaluate(task, valid, {})["completed"])
        self.assertFalse(MODULE.evaluate(task, destructive, {})["editAssertionsPassed"])

    def test_checks_two_column_geometry(self):
        task = {"taskId": "layout", "sourceVersion": "source:v1",
                "xmlAssertions": {"minVertices": 2},
                "editAssertions": {"columns": {"leftCellIds": ["left"], "rightCellIds": ["right"],
                                                   "minimumHorizontalGap": 100}},
                "citationAssertions": {"minimumCitations": 0, "mustCiteAnchors": []}}
        xml = "<mxGraphModel><root><mxCell id='left' vertex='1' parent='1'><mxGeometry x='20'/></mxCell><mxCell id='right' vertex='1' parent='1'><mxGeometry x='200'/></mxCell></root></mxGraphModel>"

        self.assertTrue(MODULE.evaluate(task, {"xml": xml, "citations": []}, {})["editAssertionsPassed"])

    def test_keeps_required_contract_separate_from_claim_metrics(self):
        result = {"xmlParseable": True, "requiredCitationContractPassed": True, "completed": True}
        summary = MODULE.summarize([result], "development")
        tasks = [{"taskId": "x", "claimAssertions": {"requiredClaims": [{
            "claimId": "c1", "requiresCitation": True,
        }]}}]
        reviewed = MODULE.claim_metrics({
            "schemaVersion": "material-rag-claim-review-v1",
            "reviewers": ["Reviewer A", "Reviewer B"],
            "adjudicationMethod": "resolve disagreements before scoring",
            "reviews": [{"taskId": "x", "claims": [{
            "claimId": "c1", "claimAnswered": True, "hasCitation": False,
            "citationSupportsClaim": False,
            "claimCorrect": True, "faithful": False,
        }]}]}, tasks)

        self.assertEqual(1.0, summary["requiredCitationContractRate"])
        self.assertEqual("not_evaluated", summary["claimMetricsStatus"])
        self.assertEqual(0.0, reviewed["claimCitationCompletenessRate"])

    def test_rejects_cherry_picked_claim_review(self):
        tasks = [{"taskId": "x", "claimAssertions": {"requiredClaims": [
            {"claimId": "c1", "requiresCitation": True},
            {"claimId": "c2", "requiresCitation": True},
        ]}}]
        review = {"schemaVersion": "material-rag-claim-review-v1",
                  "reviewers": ["Reviewer A", "Reviewer B"],
                  "adjudicationMethod": "resolve disagreements before scoring",
                  "reviews": [{"taskId": "x", "claims": [{
                      "claimId": "c1", "claimAnswered": True, "hasCitation": True,
                      "citationSupportsClaim": True,
                      "claimCorrect": True, "faithful": True,
                  }]}]}

        with self.assertRaisesRegex(ValueError, "cover the frozen claims"):
            MODULE.claim_metrics(review, tasks)

    def test_geometry_only_edit_rejects_added_cells(self):
        task = {"taskId": "layout", "inputXml": "<mxGraphModel><root><mxCell id='a' vertex='1'><mxGeometry x='0'/></mxCell></root></mxGraphModel>",
                "editAssertions": {"geometryOnly": True}}
        output = MODULE.cells("<mxGraphModel><root><mxCell id='a' vertex='1'><mxGeometry x='20'/></mxCell><mxCell id='extra' vertex='1'/></root></mxGraphModel>")

        self.assertFalse(MODULE.edit_assertions_pass(task, output))

    def test_required_edit_cell_must_be_a_visible_vertex(self):
        task = {"editAssertions": {"requiredCellValues": {"note": "Review note"}}}
        hidden = MODULE.cells("<mxGraphModel><root><mxCell id='note' value='Review note'/></root></mxGraphModel>")

        self.assertFalse(MODULE.edit_assertions_pass(task, hidden))

    def test_hidden_cell_cannot_satisfy_required_xml_label(self):
        task = {"taskId": "x", "sourceVersion": "source:v1",
                "xmlAssertions": {"minVertices": 1, "requiredLabels": ["Required"]},
                "citationAssertions": {"minimumCitations": 0, "mustCiteAnchors": []}}
        response = {"xml": "<mxGraphModel><root><mxCell id='visible' vertex='1' parent='1'/><mxCell id='hidden' value='Required'/></root></mxGraphModel>",
                    "citations": []}

        self.assertFalse(MODULE.evaluate(task, response, {})["xmlAssertionsPassed"])

    def test_rejects_duplicate_task_responses(self):
        with self.assertRaisesRegex(ValueError, "duplicate task IDs"):
            MODULE.response_map([{"taskId": "x"}, {"taskId": "x"}])

    def test_rejects_duplicate_drawio_cell_ids(self):
        task = {"taskId": "x", "sourceVersion": "source:v1",
                "xmlAssertions": {"minVertices": 1},
                "citationAssertions": {"minimumCitations": 0, "mustCiteAnchors": []}}
        response = {"xml": "<mxGraphModel><root><mxCell id='a' vertex='1' parent='1'/><mxCell id='a' vertex='1' parent='1'/></root></mxGraphModel>",
                    "citations": []}

        self.assertFalse(MODULE.evaluate(task, response, {})["xmlParseable"])
