package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.Assert.*;

public class EvalPublishingServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T05:00:00Z");

    @Test
    public void approvedCasePublishIsImmutableIdempotentAndCloneable() {
        WorkingStore working = new WorkingStore();
        EvalCaseWorkingCopy approved = approvedCase("case-1", "1", "hello");
        approved.setSourceType(EvalCaseSourceType.TRACE_DRAFT);
        approved.setCandidateId("ecc-production-link");
        working.insert(approved);
        ArtifactStore artifacts = new ArtifactStore();
        CaseVersionStore versions = new CaseVersionStore();
        ReviewStore reviews = new ReviewStore();
        reviews.insert(EvalCaseWorkingCopyReview.builder().workingCopyId(approved.getId())
                .decision("APPROVE").reviewerUserId("reviewer-1").createdAt(NOW).build());
        EvalCaseWorkingCopyService workingService = new EvalCaseWorkingCopyService(working, new EmptyTraceStore(), fixedClock());
        EvalCasePublisherService publisher = new EvalCasePublisherService(workingService, versions, artifacts, reviews, fixedClock());

        EvalCaseVersion first = publisher.publish(approved.getId(), "admin-1", EvalAdminRole.ADMIN);
        EvalCaseVersion second = publisher.publish(approved.getId(), "admin-1", EvalAdminRole.ADMIN);
        EvalCaseWorkingCopy clone = publisher.clonePublished("case-1", "1", "case-1-variant", "1",
                "editor-2", EvalAdminRole.EDITOR);

        assertEquals(first, second);
        assertEquals(1, versions.values.size());
        assertEquals(1, artifacts.values.size());
        assertEquals("reviewer-1", first.getApprovedBy());
        assertNull(working.find(approved.getId()).orElseThrow().getCandidateId());
        assertEquals(EvalCaseSourceType.PUBLISHED_CASE_CLONE, clone.getSourceType());
        assertEquals("case-1-variant", clone.getCaseId());
    }

    @Test
    public void sameCaseVersionWithDifferentContentMustConflict() {
        WorkingStore working = new WorkingStore();
        EvalCaseWorkingCopy first = approvedCase("case-1", "1", "first");
        EvalCaseWorkingCopy second = approvedCase("case-1", "1", "different");
        working.insert(first); working.insert(second);
        CaseVersionStore versions = new CaseVersionStore();
        EvalCasePublisherService publisher = new EvalCasePublisherService(
                new EvalCaseWorkingCopyService(working, new EmptyTraceStore(), fixedClock()), versions,
                new ArtifactStore(), new ReviewStore(), fixedClock());
        publisher.publish(first.getId(), "admin-1", EvalAdminRole.ADMIN);

        assertThrows(IllegalStateException.class,
                () -> publisher.publish(second.getId(), "admin-1", EvalAdminRole.ADMIN));
    }

    @Test
    public void datasetPinsPublishedVersionsAndBecomesImmutable() {
        CaseVersionStore versions = new CaseVersionStore();
        versions.insert(EvalCaseVersion.builder().caseId("case-1").caseVersion("1")
                .contentHash("hash-1").artifactRef("artifact-1").publishedAt(NOW).build());
        DatasetStore datasets = new DatasetStore();
        EvalDatasetService service = new EvalDatasetService(datasets, versions, fixedClock());
        EvalDataset dataset = service.create("core", EvalDatasetClass.CORE, "admin-1", EvalAdminRole.ADMIN);
        EvalDatasetVersion draft = service.createVersion(dataset.getId(), "core-v2",
                List.of(EvalDatasetMember.builder().caseId("case-1").caseVersion("1").build()),
                "admin-1", EvalAdminRole.ADMIN);

        EvalDatasetVersion validated = service.validate(dataset.getId(), "core-v2", "admin-1", EvalAdminRole.ADMIN);
        EvalDatasetVersion published = service.publish(dataset.getId(), "core-v2", "admin-1", EvalAdminRole.ADMIN);

        assertEquals(EvalDatasetVersionStatus.VALIDATED, validated.getStatus());
        assertEquals(EvalDatasetVersionStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getContentHash());
        assertThrows(IllegalStateException.class, () -> service.replaceMembers(dataset.getId(), "core-v2",
                published.getRevision(), List.of(), "admin-1", EvalAdminRole.ADMIN));
        assertEquals("1", datasets.find(dataset.getId(), "core-v2").orElseThrow().getMembers().get(0).getCaseVersion());
    }

    @Test
    public void historicalPublishedDatasetCanLoadItsExactCaseArtifacts() {
        WorkingStore working = new WorkingStore();
        EvalCaseWorkingCopy approved = approvedCase("historical-case", "1", "original task");
        working.insert(approved);
        ArtifactStore artifacts = new ArtifactStore();
        CaseVersionStore versions = new CaseVersionStore();
        EvalCasePublisherService publisher = new EvalCasePublisherService(
                new EvalCaseWorkingCopyService(working, new EmptyTraceStore(), fixedClock()), versions,
                artifacts, new ReviewStore(), fixedClock());
        publisher.publish(approved.getId(), "admin-1", EvalAdminRole.ADMIN);
        DatasetStore store = new DatasetStore();
        EvalDatasetService datasets = new EvalDatasetService(store, versions, fixedClock());
        EvalDataset dataset = datasets.create("core", EvalDatasetClass.CORE, "admin-1", EvalAdminRole.ADMIN);
        datasets.createVersion(dataset.getId(), "core-v1", List.of(EvalDatasetMember.builder()
                .caseId("historical-case").caseVersion("1").build()), "admin-1", EvalAdminRole.ADMIN);
        datasets.validate(dataset.getId(), "core-v1", "admin-1", EvalAdminRole.ADMIN);
        datasets.publish(dataset.getId(), "core-v1", "admin-1", EvalAdminRole.ADMIN);

        List<EvalCaseDefinition> loaded = new EvalDatasetCaseSource(datasets, publisher)
                .loadPublished(dataset.getId(), "core-v1", EvalAdminRole.ADMIN);

        assertEquals(1, loaded.size());
        assertEquals("original task", loaded.get(0).getInput().get("user"));
        assertEquals("core-v1", loaded.get(0).getDatasetVersion());
    }

    @Test
    public void sequesteredDatasetContentsAreHiddenFromOrdinaryAdmins() {
        CaseVersionStore versions = new CaseVersionStore();
        versions.insert(EvalCaseVersion.builder().caseId("sealed-ref").caseVersion("1")
                .contentHash("sealed-hash").artifactRef("external:sealed").publishedAt(NOW).build());
        DatasetStore store = new DatasetStore();
        EvalDatasetService datasets = new EvalDatasetService(store, versions, fixedClock());
        EvalDataset sealed = datasets.create("sealed", EvalDatasetClass.SEQUESTERED,
                "release-owner", EvalAdminRole.RELEASE_OWNER);
        datasets.createVersion(sealed.getId(), "release-v1", List.of(EvalDatasetMember.builder()
                .caseId("sealed-ref").caseVersion("1").build()), "release-owner", EvalAdminRole.RELEASE_OWNER);
        datasets.validate(sealed.getId(), "release-v1", "release-owner", EvalAdminRole.RELEASE_OWNER);
        datasets.publish(sealed.getId(), "release-v1", "release-owner", EvalAdminRole.RELEASE_OWNER);
        EvalCasePublisherService unavailableCases = new EvalCasePublisherService(
                new EvalCaseWorkingCopyService(new WorkingStore(), new EmptyTraceStore(), fixedClock()),
                versions, new ArtifactStore(), new ReviewStore(), fixedClock());

        assertThrows(SecurityException.class, () -> new EvalDatasetCaseSource(datasets, unavailableCases)
                .loadPublished(sealed.getId(), "release-v1", EvalAdminRole.ADMIN));
        assertThrows(SecurityException.class, () -> new EvalDatasetCoverageService(datasets, unavailableCases)
                .coverage(sealed.getId(), "release-v1", EvalAdminRole.ADMIN));
    }

    private EvalCaseWorkingCopy approvedCase(String caseId, String version, String user) {
        return EvalCaseWorkingCopy.builder().id("w_" + UUID.randomUUID()).caseId(caseId).caseVersion(version)
                .sourceType(EvalCaseSourceType.MANUAL).status(EvalCaseWorkingCopyStatus.APPROVED)
                .ownerUserId("editor-1").revision(6L).definition(EvalCaseDefinition.builder()
                        .caseId(caseId).caseVersion(version).datasetVersion("dev-draft")
                        .origin("specification-derived").risk("low").fixtureVersion("fixture-v1")
                        .xmlContractVersion("drawio-v1").input(new LinkedHashMap<>(Map.of("user", user)))
                        .privacy(new EvalCaseDefinition.Privacy("synthetic", "test-v1"))
                        .expected(new EvalCaseDefinition.Expected()).build())
                .createdAt(NOW).updatedAt(NOW).build();
    }

    private Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private static final class ArtifactStore implements IEvalCaseArtifactStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public String putIfAbsent(String hash, byte[] content) { values.putIfAbsent(hash, content); return "artifact:" + hash; }
        @Override public Optional<byte[]> read(String ref) { return Optional.ofNullable(values.get(ref.replace("artifact:", ""))); }
    }
    private static final class CaseVersionStore implements IEvalCaseVersionStore {
        private final Map<String, EvalCaseVersion> values = new LinkedHashMap<>();
        @Override public void insert(EvalCaseVersion value) { values.put(value.getCaseId() + ":" + value.getCaseVersion(), value); }
        @Override public Optional<EvalCaseVersion> find(String id, String version) { return Optional.ofNullable(values.get(id + ":" + version)); }
        @Override public List<EvalCaseVersion> list(String id) { return values.values().stream().filter(v -> id == null || id.equals(v.getCaseId())).toList(); }
        @Override public boolean retire(String id, String version, Instant retiredAt) { EvalCaseVersion current = values.get(id + ":" + version); if (current == null || current.getRetiredAt() != null) return false; values.put(id + ":" + version, current.toBuilder().retiredAt(retiredAt).build()); return true; }
    }
    private static final class DatasetStore implements IEvalDatasetStore {
        private final Map<String, EvalDataset> datasets = new LinkedHashMap<>();
        private final Map<String, EvalDatasetVersion> versions = new LinkedHashMap<>();
        @Override public void insertDataset(EvalDataset value) { datasets.put(value.getId(), value); }
        @Override public Optional<EvalDataset> findDataset(String id) { return Optional.ofNullable(datasets.get(id)); }
        @Override public List<EvalDataset> listDatasets() { return List.copyOf(datasets.values()); }
        @Override public void insert(EvalDatasetVersion value) { versions.put(key(value.getDatasetId(), value.getVersion()), value); }
        @Override public boolean update(EvalDatasetVersion value, long revision) {
            EvalDatasetVersion current = versions.get(key(value.getDatasetId(), value.getVersion()));
            if (current == null || current.getRevision() != revision) return false;
            versions.put(key(value.getDatasetId(), value.getVersion()), value); return true;
        }
        @Override public Optional<EvalDatasetVersion> find(String id, String version) { return Optional.ofNullable(versions.get(key(id, version))); }
        @Override public List<EvalDatasetVersion> listVersions(String id) { return versions.values().stream().filter(v -> id.equals(v.getDatasetId())).toList(); }
        private String key(String id, String version) { return id + ":" + version; }
    }
    private static final class WorkingStore implements IEvalCaseWorkingCopyStore {
        private final Map<String, EvalCaseWorkingCopy> values = new LinkedHashMap<>();
        @Override public Optional<EvalCaseWorkingCopy> find(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String owner, int limit, int offset) { return values.values().stream().toList(); }
        @Override public void insert(EvalCaseWorkingCopy value) { values.put(value.getId(), value); }
        @Override public boolean update(EvalCaseWorkingCopy value, long revision) { EvalCaseWorkingCopy old = values.get(value.getId()); if (old == null || old.getRevision() != revision) return false; values.put(value.getId(), value); return true; }
    }
    private static final class ReviewStore implements IEvalCaseWorkingCopyReviewStore {
        private final List<EvalCaseWorkingCopyReview> values = new ArrayList<>();
        @Override public void insert(EvalCaseWorkingCopyReview value) { values.add(value); }
        @Override public List<EvalCaseWorkingCopyReview> list(String id) { return values.stream().filter(v -> id.equals(v.getWorkingCopyId())).toList(); }
    }
    private static final class EmptyTraceStore implements ITraceToEvalStore {
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidate(String id) { return Optional.empty(); }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String id, String family) { return Optional.empty(); }
        @Override public void insertCandidate(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate value) { }
        @Override public void updateCandidateStatus(String id, org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus status) { }
        @Override public void insertReview(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview value) { }
        @Override public void insertLineage(org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage value) { }
    }
}
