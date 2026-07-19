package org.zipp.ai.domain.ingestion.model.aggregate;

import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionState;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingStage;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class ProcessingRevision {

    private final String id;
    private final String versionId;
    private final int revisionNo;
    private final String processingFingerprint;
    private final Set<Integer> excludedPages;
    private ProcessingRevisionState state = ProcessingRevisionState.PROCESSING;
    private ProcessingStage stage = ProcessingStage.CREATED;
    private int progress;
    private String gapManifestKey;
    private String errorCode;
    private Instant publishedAt;

    private ProcessingRevision(String id, String versionId, int revisionNo,
                               String processingFingerprint, Set<Integer> excludedPages) {
        this.id = requireText(id, "id");
        this.versionId = requireText(versionId, "versionId");
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revisionNo must be positive");
        }
        this.revisionNo = revisionNo;
        this.processingFingerprint = requireText(processingFingerprint, "processingFingerprint");
        TreeSet<Integer> pages = new TreeSet<>(Objects.requireNonNull(excludedPages, "excludedPages"));
        if (pages.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("excluded page numbers must be positive");
        }
        this.excludedPages = Collections.unmodifiableSet(pages);
    }

    public static ProcessingRevision start(String id, String versionId, int revisionNo,
                                           String processingFingerprint, Set<Integer> excludedPages) {
        return new ProcessingRevision(id, versionId, revisionNo, processingFingerprint, excludedPages);
    }

    public void advanceTo(ProcessingStage nextStage, int progressPercent) {
        requireProcessing();
        ProcessingStage requested = Objects.requireNonNull(nextStage, "nextStage");
        // Every stage is a publication prerequisite, even when that stage has no work for a file.
        if (requested.ordinal() != stage.ordinal() + 1) {
            throw new IllegalArgumentException("processing stages must advance exactly one step");
        }
        if (progressPercent < progress || progressPercent < 0 || progressPercent > 99) {
            throw new IllegalArgumentException("progress must be monotonic and below publication completion");
        }
        stage = requested;
        progress = progressPercent;
    }

    public void publishReady(Instant publicationTime) {
        requirePublishingStage();
        state = ProcessingRevisionState.READY;
        progress = 100;
        publishedAt = Objects.requireNonNull(publicationTime, "publicationTime");
    }

    public void publishPartial(String gapManifestKey, Instant publicationTime) {
        requirePublishingStage();
        this.gapManifestKey = requireText(gapManifestKey, "gapManifestKey");
        state = ProcessingRevisionState.PARTIAL_READY;
        progress = 100;
        publishedAt = Objects.requireNonNull(publicationTime, "publicationTime");
    }

    public void fail(String stableErrorCode) {
        requireProcessing();
        errorCode = requireText(stableErrorCode, "stableErrorCode");
        state = ProcessingRevisionState.FAILED;
    }

    private void requirePublishingStage() {
        requireProcessing();
        if (stage != ProcessingStage.PUBLISHING) {
            throw new IllegalStateException("revision has not reached the publishing gate");
        }
    }

    private void requireProcessing() {
        if (state != ProcessingRevisionState.PROCESSING) {
            throw new IllegalStateException("revision is immutable after reaching a terminal state");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String versionId() { return versionId; }
    public int revisionNo() { return revisionNo; }
    public String processingFingerprint() { return processingFingerprint; }
    public Set<Integer> excludedPages() { return excludedPages; }
    public ProcessingRevisionState state() { return state; }
    public ProcessingStage stage() { return stage; }
    public int progress() { return progress; }
    public String gapManifestKey() { return gapManifestKey; }
    public String errorCode() { return errorCode; }
    public Instant publishedAt() { return publishedAt; }
}
