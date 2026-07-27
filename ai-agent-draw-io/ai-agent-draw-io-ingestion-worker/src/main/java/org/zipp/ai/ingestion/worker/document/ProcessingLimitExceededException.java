package org.zipp.ai.ingestion.worker.document;

/** Signals a deterministic processing-budget violation that must not be retried. */
public final class ProcessingLimitExceededException extends IllegalArgumentException {

    public ProcessingLimitExceededException(String message) {
        super(message);
    }
}
