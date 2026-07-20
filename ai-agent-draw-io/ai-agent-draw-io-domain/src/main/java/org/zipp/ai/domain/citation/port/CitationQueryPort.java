package org.zipp.ai.domain.citation.port;

import org.zipp.ai.domain.citation.model.valobj.CellCitationView;
import org.zipp.ai.domain.citation.model.valobj.AnswerCitationView;

import java.util.List;

/** Read side for the source panel; implementations must fence every query by owner. */
public interface CitationQueryPort {
    List<CellCitationView> findCellCitations(String ownerKey, String diagramId,
                                             String cellId, Long canvasVersion, String provenanceRef);

    List<AnswerCitationView> findAnswerCitations(String ownerKey, String diagramId,
                                                 List<String> messageIds);
}
