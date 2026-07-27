package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;
import org.zipp.ai.domain.material.port.MaterialDeletionVectorPort;
import org.zipp.ai.domain.material.port.MaterialDeletionWorkPort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialDeletionTaskHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void activeReadLeaseDefersDeletionWithoutTouchingExternalContent() {
        FakeWork work = new FakeWork(task(MaterialDeletionStage.WAIT_LEASES));
        work.activeLease = true;
        MaterialDeletionTaskHandler handler = handler(work, vectors -> fail("vectors must not be deleted"),
                objects -> fail("objects must not be deleted"));

        handler.handle(work.lease());

        assertEquals(MaterialDeletionTaskStatus.RETRY, work.saved.status());
        assertEquals(MaterialDeletionStage.WAIT_LEASES, work.saved.stage());
        assertEquals("ACTIVE_READ_LEASE", work.saved.errorCode());
    }

    @Test
    void vectorAndObjectStagesDeleteExactRecordedIdentitiesBeforeAdvancing() {
        FakeWork vectorsWork = new FakeWork(task(MaterialDeletionStage.DELETE_VECTORS));
        List<MaterialVectorLocation> deletedVectors = new java.util.ArrayList<>();
        handler(vectorsWork, vectors -> {
            deletedVectors.addAll(vectors);
            return receipt(vectors.size());
        }, objects -> receipt(objects.size())).handle(vectorsWork.lease());

        FakeWork objectsWork = new FakeWork(task(MaterialDeletionStage.DELETE_OBJECTS));
        List<MaterialObjectVersion> deletedObjects = new java.util.ArrayList<>();
        handler(objectsWork, vectors -> receipt(vectors.size()), objects -> {
            deletedObjects.addAll(objects);
            return receipt(objects.size());
        }).handle(objectsWork.lease());

        assertEquals(List.of(new MaterialVectorLocation("index-1", "namespace-1", "vector-1")),
                deletedVectors);
        assertEquals(MaterialDeletionStage.DELETE_OBJECTS, vectorsWork.saved.stage());
        assertEquals(List.of(new MaterialObjectVersion("MATERIALS", "object/key", "object-version")),
                deletedObjects);
        assertEquals(MaterialDeletionStage.PURGE_DATABASE, objectsWork.saved.stage());
    }

    @Test
    void externalFailureKeepsTaskRetryableAndNeverPurgesDatabase() {
        FakeWork work = new FakeWork(task(MaterialDeletionStage.DELETE_OBJECTS));
        MaterialDeletionTaskHandler handler = handler(work, vectors -> receipt(vectors.size()), objects -> {
            throw new IllegalStateException("S3 unavailable");
        });

        handler.handle(work.lease());

        assertEquals(MaterialDeletionTaskStatus.RETRY, work.saved.status());
        assertEquals(MaterialDeletionStage.DELETE_OBJECTS, work.saved.stage());
        assertFalse(work.purged);
    }

    @Test
    void anotherVectorProfileLeavesTheSameStageRetryable() {
        FakeWork work = new FakeWork(task(MaterialDeletionStage.DELETE_VECTORS));
        handler(work, vectors -> MaterialDeletionReceipt.vector(0, List.of(), "old-index", false),
                objects -> receipt(objects.size())).handle(work.lease());

        assertEquals(MaterialDeletionTaskStatus.RETRY, work.saved.status());
        assertEquals(MaterialDeletionStage.DELETE_VECTORS, work.saved.stage());
        assertEquals("VECTOR_PROFILE_REQUIRED", work.saved.errorCode());
    }

    private MaterialDeletionTaskHandler handler(FakeWork work, MaterialDeletionVectorPort vectors,
                                                MaterialDeletionObjectPort objects) {
        return new MaterialDeletionTaskHandler(work, vectors, objects,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MaterialDeletionTask task(MaterialDeletionStage stage) {
        MaterialDeletionTask task = MaterialDeletionTask.rehydrate("task_1", "material_1", 3,
                stage, MaterialDeletionTaskStatus.QUEUED, 0, NOW, null, null, 0, null);
        task.claim("deletion-worker", NOW, java.time.Duration.ofMinutes(5));
        return task;
    }

    private MaterialDeletionReceipt receipt(int count) {
        return MaterialDeletionReceipt.from(count, List.of("request-1"));
    }

    private static final class FakeWork implements MaterialDeletionWorkPort {
        private final MaterialDeletionTask task;
        private boolean activeLease;
        private boolean purged;
        private MaterialDeletionTask saved;

        private FakeWork(MaterialDeletionTask task) {
            this.task = task;
        }

        private MaterialDeletionLease lease() {
            return new MaterialDeletionLease(task, task.fenceToken());
        }

        @Override public Optional<MaterialDeletionLease> claim(String workerId, Instant now,
                                                               java.time.Duration leaseDuration) {
            return Optional.empty();
        }
        @Override public boolean hasActiveReadLeases(String materialId, Instant now) { return activeLease; }
        @Override public List<MaterialVectorLocation> findVectorLocations(String materialId) {
            return List.of(new MaterialVectorLocation("index-1", "namespace-1", "vector-1"));
        }
        @Override public List<MaterialObjectVersion> findObjectVersions(String materialId) {
            return List.of(new MaterialObjectVersion("MATERIALS", "object/key", "object-version"));
        }
        @Override public boolean prepareAndSave(MaterialDeletionTask changed,
                                                MaterialDeletionStage expectedStage, long fenceToken,
                                                Instant preparedAt) {
            saved = changed; return true;
        }
        @Override public boolean recordVectorsAndSave(MaterialDeletionTask changed,
                                                      MaterialDeletionStage expectedStage, long fenceToken,
                                                      MaterialDeletionReceipt receipt, Instant deletedAt) {
            saved = changed; return true;
        }
        @Override public boolean recordObjectsAndSave(MaterialDeletionTask changed,
                                                      MaterialDeletionStage expectedStage, long fenceToken,
                                                      MaterialDeletionReceipt receipt, Instant deletedAt) {
            saved = changed; return true;
        }
        @Override public boolean purgeAndComplete(MaterialDeletionTask changed,
                                                  MaterialDeletionStage expectedStage, long fenceToken,
                                                  Instant deletedAt) {
            purged = true; saved = changed; return true;
        }
        @Override public boolean saveRetry(MaterialDeletionTask changed,
                                           MaterialDeletionStage expectedStage, long fenceToken) {
            saved = changed; return true;
        }
        @Override public int requeueExpiredLeases(Instant now, int limit) { return 0; }
    }
}
