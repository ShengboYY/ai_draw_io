package org.zipp.ai.domain.agent.service.analysis;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;

import java.util.List;

/**
 * Reads normalized mxCell facts without running any diagram quality heuristic.
 *
 * <p>The draft store owns XML safety and reference validation. This reader only projects the
 * already-validated document for inspection, rendering, and mutation-scope checks.</p>
 */
public final class DrawioCellDocumentReader {

    public List<CanvasCellData> read(String mxGraphModelXml) {
        try {
            CanvasDocumentModel document = CanvasDocumentModel.parse(mxGraphModelXml);
            if (!document.rootPresent()) {
                throw new IllegalArgumentException("DRAFT_ROOT_REQUIRED");
            }
            return document.cells();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("DRAFT_XML_INVALID", exception);
        }
    }
}
