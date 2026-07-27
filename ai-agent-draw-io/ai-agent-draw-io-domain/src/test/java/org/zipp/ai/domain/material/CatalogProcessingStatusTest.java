package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.valobj.CatalogProcessingStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogProcessingStatusTest {
    @Test
    void mapsInternalRevisionStagesToStableCatalogStatuses() {
        assertEquals(CatalogProcessingStatus.EXTRACTING,
                CatalogProcessingStatus.from("PROCESSING", "PROCESSING", "EXTRACTING"));
        assertEquals(CatalogProcessingStatus.OCR_VISUAL,
                CatalogProcessingStatus.from("PROCESSING", "PROCESSING", "OCR_VISUAL"));
        assertEquals(CatalogProcessingStatus.INDEXING,
                CatalogProcessingStatus.from("PROCESSING", "PROCESSING", "PUBLISHING"));
        assertEquals(CatalogProcessingStatus.PARTIAL_READY,
                CatalogProcessingStatus.from("READY", "PARTIAL_READY", "PUBLISHING"));
        assertEquals(CatalogProcessingStatus.FAILED,
                CatalogProcessingStatus.from("PROCESSING", "FAILED", "EXTRACTING"));
        assertEquals(CatalogProcessingStatus.EXTRACTING,
                CatalogProcessingStatus.from("READY", "PROCESSING", "EXTRACTING"));
        assertEquals(CatalogProcessingStatus.OCR_VISUAL,
                CatalogProcessingStatus.from("READY", "PROCESSING", "VISUAL_ANALYSIS"));
        assertEquals(CatalogProcessingStatus.INDEXING,
                CatalogProcessingStatus.from("READY", "PROCESSING", "EVIDENCE_BUILD"));
    }
}
