package org.zipp.ai.domain.retrieval.internal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.RetrievalRoute;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSufficiencyEvaluatorTest {
    private final EvidenceSufficiencyEvaluator evaluator = new EvidenceSufficiencyEvaluator();

    @Test
    void rejectsAHitWithNoAbsoluteQueryOverlap() {
        var result = evaluator.evaluate("What is the Agile iteration workflow?", RetrievalRoute.TEXT,
                List.of(item("This page only discusses annual financial reporting.")));

        assertFalse(result.sufficient());
    }

    @Test
    void requiresNumbersFromTheQuestionToAppearInEvidence() {
        var result = evaluator.evaluate("What changed in version 2.1?", RetrievalRoute.TEXT,
                List.of(item("Version 2.0 introduced the workflow.")));

        assertFalse(result.sufficient());
    }

    @Test
    void acceptsBoundedSummaryCoverage() {
        var result = evaluator.evaluate("总结整份 Agile 文档", RetrievalRoute.TEXT,
                List.of(item("Agile teams plan iteratively, deliver in short cycles, review outcomes, "
                                + "and adapt their backlog continuously with stakeholder feedback and retrospectives."),
                        item("The guide also covers roles, iteration review, prioritization, risk controls, "
                                + "delivery feedback loops, and continuous improvement practices for Agile teams.")));

        assertTrue(result.sufficient());
    }

    @Test
    void rejectsComparisonWhenOnlyOneRequiredSubjectIsCovered() {
        var result = evaluator.evaluate("Compare Kafka and RabbitMQ", RetrievalRoute.TEXT,
                List.of(item("Kafka is an event streaming platform with partitioned logs.")));

        assertFalse(result.sufficient());
    }

    private EvidenceBundleItem item(String text) {
        return new EvidenceBundleItem("cite_1", "evidence-1", "material-1",
                "version-1", "revision-1", "Agile Practice Guide", 1, "TEXT", text);
    }
}
