package org.zipp.ai.domain.citation.answer;

/** How an answer claim is supported; only AI_KNOWLEDGE may omit evidence. */
public enum AnswerSupportType { DIRECT, SYNTHESIZED, VISUAL_VERIFIED, AI_KNOWLEDGE }
