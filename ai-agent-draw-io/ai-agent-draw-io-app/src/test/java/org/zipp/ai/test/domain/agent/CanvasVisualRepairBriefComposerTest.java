package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualRepairBriefComposer;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasVisualRepairBriefComposerTest {

    @Test
    public void briefContainsBoundedStructuredEvidenceWithoutImagesOrRawJson() {
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.TEXT_READABILITY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .anchorLabels(List.of("data:image/png;base64,SECRET", "A".repeat(300), "API"))
                .region("center")
                .evidence("data:image/png;base64,SECRET " + "E".repeat(1000))
                .repairInstruction("Increase the visible label size." + "I".repeat(1000))
                .build();

        String brief = new CanvasVisualRepairBriefComposer().compose(
                "Make the API readable data:image/png;base64,SECRET",
                7L,
                "sha256:abc",
                List.of(
                        issue,
                        issue(CanvasVisualIssueType.EDGE_TRACEABILITY, "second issue"),
                        issue(CanvasVisualIssueType.STYLE_COHERENCE, "third issue"),
                        issue(CanvasVisualIssueType.LAYOUT_HIERARCHY, "fourth issue")));

        assertTrue(brief.startsWith("[Visual Review Continuation]"));
        assertTrue(brief.contains("not a new user request"));
        assertTrue(brief.contains("Make the API readable"));
        assertTrue(brief.contains("version=7"));
        assertTrue(brief.contains("sha256:abc"));
        assertTrue(brief.contains("Preserve every unmentioned"));
        assertTrue(brief.length() < 1800);
        assertFalse(brief.contains("data:image"));
        assertFalse(brief.contains("fourth issue"));
        assertFalse(brief.contains("{"));
    }

    private CanvasVisualIssue issue(CanvasVisualIssueType type, String evidence) {
        return CanvasVisualIssue.builder()
                .type(type)
                .severity(CanvasVisualIssueSeverity.MINOR)
                .evidence(evidence)
                .repairInstruction("Apply a local visual correction")
                .build();
    }
}
