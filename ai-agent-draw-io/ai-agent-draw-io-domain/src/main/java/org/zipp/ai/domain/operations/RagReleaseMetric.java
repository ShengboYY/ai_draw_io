package org.zipp.ai.domain.operations;

/** Versioned WP8 release metrics. Zero-tolerance boundaries are compared exactly. */
public enum RagReleaseMetric {
    TEXT_EVIDENCE_RECALL_AT_10(Direction.MINIMUM, 0.90),
    VISUAL_TABLE_RECALL_AT_10(Direction.MINIMUM, 0.80),
    CANDIDATE_RECALL_AT_40(Direction.MINIMUM, 0.95),
    POST_RERANK_RECALL_AT_16(Direction.MINIMUM, 0.92),
    PRECISION_AT_5(Direction.MINIMUM, 0.80),
    MRR_AT_10(Direction.MINIMUM, 0.80),
    EXACT_TERM_RECALL_AT_5(Direction.MINIMUM, 0.95),
    REQUIRED_FACET_COVERAGE(Direction.MINIMUM, 0.90),
    CITATION_PRECISION(Direction.MINIMUM, 0.98),
    CLAIM_COMPLETENESS(Direction.MINIMUM, 0.90),
    CLAIM_EVIDENCE_RECALL(Direction.MINIMUM, 0.90),
    KEY_CLAIM_SUPPORT_ACCURACY(Direction.MINIMUM, 0.92),
    CLAIM_VERIFIER_PRECISION(Direction.MINIMUM, 0.98),
    CRITICAL_UNSUPPORTED_CLAIM_PASS_RATE(Direction.MAXIMUM, 0.00),
    NO_ANSWER_FALSE_SUPPORTED_RATE(Direction.MAXIMUM, 0.02),
    NO_ANSWER_FALSE_ABSTENTION_RATE(Direction.MAXIMUM, 0.10),
    BUNDLE_DUPLICATE_RATE(Direction.MAXIMUM, 0.10),
    EXPLICIT_ONLY_ESCAPE_RATE(Direction.MAXIMUM, 0.00),
    CROSS_OWNER_SOURCE_RATE(Direction.MAXIMUM, 0.00),
    SELECTED_CELL_RESOLUTION_ACCURACY(Direction.MINIMUM, 1.00),
    AMBIGUOUS_TARGET_AUTO_RESOLUTION_RATE(Direction.MAXIMUM, 0.00),
    STYLE_REQUEST_RETRIEVAL_RATE(Direction.MAXIMUM, 0.00),
    EVIDENCE_ANSWER_CANVAS_MUTATION_RATE(Direction.MAXIMUM, 0.00);

    private final Direction direction;
    private final double boundary;

    RagReleaseMetric(Direction direction, double boundary) {
        this.direction = direction;
        this.boundary = boundary;
    }

    public boolean passes(double value) {
        return direction == Direction.MINIMUM ? value >= boundary : value <= boundary;
    }

    public double passingBoundary() {
        return boundary;
    }

    private enum Direction { MINIMUM, MAXIMUM }
}
