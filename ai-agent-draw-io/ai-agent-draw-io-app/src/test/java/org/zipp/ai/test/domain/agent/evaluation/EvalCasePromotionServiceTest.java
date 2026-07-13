package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceFindingViewService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.Assert.*;

public class EvalCasePromotionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T01:00:00Z");

    @Test
    public void repeatedDraftPromoteReturnsTheSameWorkingCopy() {
        Fixture fixture = new Fixture(EvalCandidateStatus.DRAFT_READY);

        EvalCasePromotionResult first = fixture.promotions.promote("candidate-1", "trace-case", "1",
                "admin-1", EvalAdminRole.ADMIN);
        EvalCasePromotionResult second = fixture.promotions.promote("candidate-1", "ignored", "2",
                "admin-1", EvalAdminRole.ADMIN);

        assertEquals(EvalCasePromotionResult.Status.CREATED, first.status());
        assertEquals(EvalCasePromotionResult.Status.EXISTING_DRAFT, second.status());
        assertEquals(first.workingCopyId(), second.workingCopyId());
        assertEquals(1, fixture.working.values.size());
    }

    @Test
    public void publicationTransfersRestrictedLinkAndSeversPublicArtifacts() {
        Fixture fixture = new Fixture(EvalCandidateStatus.DRAFT_READY);
        EvalCasePromotionResult promoted = fixture.promotions.promote("candidate-1", "trace-case", "1",
                "admin-1", EvalAdminRole.ADMIN);
        EvalCaseWorkingCopy copy = fixture.working.values.get(promoted.workingCopyId());
        copy.setStatus(EvalCaseWorkingCopyStatus.APPROVED);

        EvalCaseVersion published = fixture.promotions.publish(copy.getId(), "admin-1", EvalAdminRole.ADMIN);
        EvalCasePromotionResult repeated = fixture.promotions.promote("candidate-1", "other", "9",
                "admin-1", EvalAdminRole.ADMIN);

        assertEquals(EvalCasePromotionResult.Status.ALREADY_PUBLISHED, repeated.status());
        assertEquals(EvalCandidateStatus.PUBLISHED, fixture.trace.candidate.getStatus());
        assertNull(fixture.working.values.get(copy.getId()).getCandidateId());
        assertEquals(copy.getId(), fixture.links.byCandidate.get("candidate-1").getWorkingCopyId());
        String artifact = new String(fixture.artifacts.values.get(published.getContentHash()), StandardCharsets.UTF_8);
        assertFalse(artifact.contains("candidate-1"));
        assertFalse(artifact.contains("sourceRunId"));
        assertFalse(artifact.contains("run-production-1"));
        assertFalse(artifact.contains("raw production payload"));
    }

    @Test
    public void privacyFailureCannotCreatePublicationLink() {
        Fixture fixture = new Fixture(EvalCandidateStatus.DRAFT_READY);
        EvalCasePromotionResult promoted = fixture.promotions.promote("candidate-1", "trace-case", "1",
                "admin-1", EvalAdminRole.ADMIN);
        EvalCaseWorkingCopy copy = fixture.working.values.get(promoted.workingCopyId());
        copy.setStatus(EvalCaseWorkingCopyStatus.APPROVED);
        copy.getDefinition().getInput().put("sourceRunId", "run-production-1");

        EvalControlPlaneException failure = assertThrows(EvalControlPlaneException.class,
                () -> fixture.promotions.publish(copy.getId(), "admin-1", EvalAdminRole.ADMIN));

        assertEquals(EvalControlPlaneErrorCode.VALIDATION_FAILED, failure.getCode());
        assertTrue(fixture.links.byCandidate.isEmpty());
        assertTrue(fixture.versions.values.isEmpty());
    }

    @Test
    public void rejectedFindingCannotBePromoted() {
        Fixture fixture = new Fixture(EvalCandidateStatus.REJECTED);

        EvalControlPlaneException failure = assertThrows(EvalControlPlaneException.class,
                () -> fixture.promotions.promote("candidate-1", "trace-case", "1",
                        "admin-1", EvalAdminRole.ADMIN));

        assertEquals(EvalControlPlaneErrorCode.INVALID_STATE_TRANSITION, failure.getCode());
    }

    @Test
    public void reviewerCanFollowTheRestrictedFindingBacklinkBeforeAndAfterPublication() {
        Fixture fixture = new Fixture(EvalCandidateStatus.DRAFT_READY);
        EvalCasePromotionResult promoted = fixture.promotions.promote("candidate-1", "trace-case", "1",
                "admin-1", EvalAdminRole.ADMIN);

        TraceFindingView before = fixture.promotions.sourceFinding(promoted.workingCopyId(),
                "reviewer-1", EvalAdminRole.REVIEWER);
        EvalCaseWorkingCopy copy = fixture.working.values.get(promoted.workingCopyId());
        copy.setStatus(EvalCaseWorkingCopyStatus.APPROVED);
        fixture.promotions.publish(copy.getId(), "admin-1", EvalAdminRole.ADMIN);
        TraceFindingView after = fixture.promotions.sourceFinding(promoted.workingCopyId(),
                "reviewer-1", EvalAdminRole.REVIEWER);

        assertEquals("run-production-1", before.sourceRunId());
        assertEquals(before.candidateId(), after.candidateId());
    }

    @Test
    public void llmDraftServiceHasNoPublisherCapability() {
        assertFalse(Arrays.stream(org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalDraftService.class
                .getDeclaredFields()).anyMatch(field -> EvalCasePublisherService.class.isAssignableFrom(field.getType())
                || EvalCasePromotionService.class.isAssignableFrom(field.getType())));
    }

    private static final class Fixture {
        private final WorkingStore working = new WorkingStore();
        private final TraceStore trace;
        private final LinkStore links = new LinkStore();
        private final VersionStore versions = new VersionStore();
        private final ArtifactStore artifacts = new ArtifactStore();
        private final EvalCasePromotionService promotions;

        private Fixture(EvalCandidateStatus status) {
            trace = new TraceStore(status);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            EvalCaseWorkingCopyService workingService = new EvalCaseWorkingCopyService(working, trace, clock);
            EvalCaseValidationService validation = new EvalCaseValidationService(workingService,
                    new IEvalCaseEvidenceStore() {
                        @Override public void insert(EvalCaseEvidence evidence) { }
                        @Override public List<EvalCaseEvidence> list(String workingCopyId) { return List.of(); }
                    }, clock);
            EvalCasePublisherService publisher = new EvalCasePublisherService(workingService, versions, artifacts,
                    new IEvalCaseWorkingCopyReviewStore() {
                        @Override public void insert(EvalCaseWorkingCopyReview review) { }
                        @Override public List<EvalCaseWorkingCopyReview> list(String workingCopyId) { return List.of(); }
                    }, clock);
            promotions = new EvalCasePromotionService(workingService, publisher, validation, trace, links,
                    new TraceFindingViewService(trace), clock);
        }
    }

    private static final class WorkingStore implements IEvalCaseWorkingCopyStore {
        private final Map<String, EvalCaseWorkingCopy> values = new LinkedHashMap<>();
        @Override public Optional<EvalCaseWorkingCopy> find(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<EvalCaseWorkingCopy> findByCandidateId(String candidateId) {
            return values.values().stream().filter(value -> candidateId.equals(value.getCandidateId())).findFirst();
        }
        @Override public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String owner, int limit, int offset) { return List.copyOf(values.values()); }
        @Override public void insert(EvalCaseWorkingCopy value) { values.put(value.getId(), value); }
        @Override public EvalCaseWorkingCopy insertTraceDraftIfAbsent(EvalCaseWorkingCopy value) {
            return findByCandidateId(value.getCandidateId()).orElseGet(() -> { insert(value); return value; });
        }
        @Override public boolean update(EvalCaseWorkingCopy value, long revision) {
            EvalCaseWorkingCopy current = values.get(value.getId());
            if (current == null || current.getRevision() != revision) return false;
            values.put(value.getId(), value); return true;
        }
    }

    private static final class TraceStore implements ITraceToEvalStore {
        private final EvalCaseCandidate candidate;
        private final EvalCaseDraft draft;
        private TraceStore(EvalCandidateStatus status) {
            candidate = EvalCaseCandidate.builder().id("candidate-1").sourceRunId("run-production-1")
                    .failureFamily("latency_regression").ruleId("latency_threshold")
                    .evidenceSummary("slow response").risk("high").discoveredAt(NOW)
                    .policyVersion("selector-v1").status(status).createdBy("analyzer").build();
            draft = EvalCaseDraft.builder().id("draft-1").candidateId(candidate.getId())
                    .failureSummary("synthetic slow edit").suspectedFailureFamily("latency_regression")
                    .userTurns(List.of("Rename API to Gateway")).expectedRoute("edit_existing")
                    .suggestedAssertions(List.of("Gateway exists")).confidence("high")
                    .sanitizerVersion("sanitizer-v1").modelVersion("draft-model-v1").createdAt(NOW).build();
        }
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return candidate.getId().equals(id) ? Optional.of(candidate) : Optional.empty(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate value) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { candidate.setStatus(status); }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public Optional<EvalCaseDraft> findLatestDraft(String id) { return candidate.getId().equals(id) ? Optional.of(draft) : Optional.empty(); }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }

    private static final class LinkStore implements IEvalCandidatePromotionLinkStore {
        private final Map<String, EvalCandidatePromotionLink> byCandidate = new LinkedHashMap<>();
        @Override public Optional<EvalCandidatePromotionLink> findByCandidateId(String id) { return Optional.ofNullable(byCandidate.get(id)); }
        @Override public Optional<EvalCandidatePromotionLink> findByWorkingCopyId(String id) { return byCandidate.values().stream().filter(value -> id.equals(value.getWorkingCopyId())).findFirst(); }
        @Override public EvalCandidatePromotionLink insertIfAbsent(EvalCandidatePromotionLink link) { byCandidate.putIfAbsent(link.getCandidateId(), link); return byCandidate.get(link.getCandidateId()); }
    }

    private static final class VersionStore implements IEvalCaseVersionStore {
        private final Map<String, EvalCaseVersion> values = new LinkedHashMap<>();
        @Override public void insert(EvalCaseVersion value) { values.put(value.getCaseId() + ":" + value.getCaseVersion(), value); }
        @Override public Optional<EvalCaseVersion> find(String id, String version) { return Optional.ofNullable(values.get(id + ":" + version)); }
        @Override public List<EvalCaseVersion> list(String id) { return List.copyOf(values.values()); }
        @Override public boolean retire(String id, String version, Instant retiredAt) { return false; }
    }

    private static final class ArtifactStore implements IEvalCaseArtifactStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public String putIfAbsent(String hash, byte[] content) { values.putIfAbsent(hash, content); return "artifact:" + hash; }
        @Override public Optional<byte[]> read(String ref) { return Optional.ofNullable(values.get(ref.replace("artifact:", ""))); }
    }
}
