package org.zipp.ai.domain.material.port;

public interface CatalogIdFactory {
    String nextId(String prefix);

    default String nextChartbookId() {
        return nextId("cb");
    }

    default String nextMaterialScopeLinkId() {
        return nextId("msl");
    }

    default String nextProcessingRevisionId() {
        return nextId("rev");
    }

    default String nextProcessingJobId() {
        return nextId("job");
    }
}
