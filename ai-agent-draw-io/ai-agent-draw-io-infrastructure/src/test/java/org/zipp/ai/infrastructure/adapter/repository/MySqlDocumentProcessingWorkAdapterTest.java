package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.DocumentExtractionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionArtifactPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MySqlDocumentProcessingWorkAdapterTest {

    @Test
    void readsAnExactFormalObjectPinForExtraction() {
        DocumentExtractionWorkPO po = new DocumentExtractionWorkPO();
        po.setRevisionId("rev_1");
        po.setDetectedMediaType("application/pdf");
        po.setRevisionFenceGeneration(4);
        po.setMaterialLifecycleGeneration(9);
        po.setProcessingFingerprint("d".repeat(64));
        po.setObjectKey("original/blob_1/hash");
        po.setObjectVersionId("version-7");
        po.setContentSha256("a".repeat(64));
        po.setByteSize(42);
        po.setContentType("application/pdf");
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class,
                (method, args) -> "selectExtractionWork".equals(method) ? po
                        : unsupported(method));
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, unusedJobs());

        Optional<RevisionExtractionWork> result = adapter.findExtractionWork("rev_1",
                new WorkerFence("job_1", "worker-1", 2));

        assertEquals("version-7", result.orElseThrow().original().objectVersionId());
        assertEquals(4, result.orElseThrow().revisionFenceGeneration());
        assertEquals("d".repeat(64), result.orElseThrow().processingFingerprint());
    }

    @Test
    void staleRevisionGenerationCannotPersistPagesOrQueueTheNextStage() {
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, (method, args) -> {
            if ("countCurrentFence".equals(method)) {
                return 0;
            }
            throw new AssertionError("stale fence must fail before " + method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class,
                (method, args) -> { throw new AssertionError("next job must not be queued"); });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionExtractionWork source = new RevisionExtractionWork("rev_1", "application/pdf", 3, 8,
                "d".repeat(64),
                artifact("original", "original-version"));
        NativePageResult page = new NativePageResult(1, 100, 200, true,
                artifact("page.png", "image-version"), artifact("native.json.gz", "native-version"), null);

        assertFalse(adapter.commitNativeExtraction(source, List.of(page), List.of(nextJob()),
                new WorkerFence("job_1", "worker-1", 2)));
    }

    @Test
    void nativeCommitPinsArtifactsAndAtomicallyQueuesOcr() {
        AtomicReference<RevisionArtifactPO> lastArtifact = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        // Capture each inserted immutable artifact so the adapter can verify the identity it just persisted.
        AtomicReference<org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO> persistedPage =
                new AtomicReference<>();
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, new Call() {
            @Override public Object invoke(String method, Object[] args) {
                return switch (method) {
                    case "countCurrentFence", "updatePageCount", "advanceRevision" -> 1;
                    case "insertPage" -> {
                        persistedPage.set((org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO) args[0]);
                        yield 1;
                    }
                    case "selectPage" -> persistedPage.get();
                    case "insertArtifact" -> {
                        lastArtifact.set((RevisionArtifactPO) args[0]);
                        yield 1;
                    }
                    case "selectArtifact" -> lastArtifact.get();
                    default -> unsupported(method);
                };
            }
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionExtractionWork source = new RevisionExtractionWork("rev_1", "application/pdf", 3, 8,
                "d".repeat(64),
                artifact("original", "original-version"));
        NativePageResult page = new NativePageResult(1, 100, 200, true,
                artifact("page.png", "image-version"), artifact("native.json.gz", "native-version"), null);

        adapter.commitNativeExtraction(source, List.of(page), List.of(nextJob()),
                new WorkerFence("job_1", "worker-1", 2));

        assertEquals(ProcessingJobStage.OCR_SELECTED_PAGES.name(), queued.get().getStage());
        assertEquals("NATIVE_EXTRACTION", lastArtifact.get().getArtifactKind());
    }

    private static StoredArtifact artifact(String key, String version) {
        return new StoredArtifact(key, version, "a".repeat(64), 10, "application/octet-stream");
    }

    private static ProcessingJob nextJob() {
        return ProcessingJob.enqueue("job_ocr", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.OCR_SELECTED_PAGES, "page:1", "b".repeat(64), 0,
                Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static IProcessingJobMapper unusedJobs() {
        return proxy(IProcessingJobMapper.class, (method, args) -> unsupported(method));
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException(method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
