package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructureResult;
import org.zipp.ai.domain.ingestion.model.valobj.NativePageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionExtractionWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageBatch;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionStructureWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionVisualWork;
import org.zipp.ai.domain.ingestion.model.valobj.VisualProcessingResult;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;

import java.util.List;
import java.util.Optional;

/** Fenced transaction boundary for the document-processing aggregate. */
public interface DocumentProcessingWorkPort {
    Optional<RevisionExtractionWork> findExtractionWork(String revisionId, WorkerFence fence);
    boolean commitNativeExtraction(RevisionExtractionWork work, List<NativePageResult> pages,
                                   List<ProcessingJob> nextJobs, WorkerFence fence);
    Optional<RevisionPageBatch> findPageBatch(String revisionId, WorkerFence fence);
    boolean commitOcr(RevisionPageBatch batch, List<OcrPageResult> pages,
                      ProcessingJob nextJob, WorkerFence fence);
    boolean commitCanonical(RevisionPageBatch batch, List<CanonicalPageResult> pages,
                            ProcessingJob nextJob, WorkerFence fence);
    Optional<RevisionStructureWork> findStructureWork(String revisionId, WorkerFence fence);
    boolean commitStructure(RevisionStructureWork work, DocumentStructureResult result,
                            ProcessingJob nextJob, WorkerFence fence);
    Optional<RevisionVisualWork> findVisualWork(String revisionId, WorkerFence fence);
    boolean commitVisualCrops(RevisionVisualWork work, VisualProcessingResult result,
                              ProcessingJob nextJob, WorkerFence fence);
}
