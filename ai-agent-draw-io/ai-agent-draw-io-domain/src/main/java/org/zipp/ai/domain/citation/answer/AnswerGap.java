package org.zipp.ai.domain.citation.answer;

/** A bounded statement that the prepared evidence could not safely answer. */
public record AnswerGap(String gapKey, String facetKey, String reasonCode) { }
