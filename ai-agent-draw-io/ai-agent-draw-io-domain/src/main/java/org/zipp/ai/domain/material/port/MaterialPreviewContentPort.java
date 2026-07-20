package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.MaterialPreviewImage;

public interface MaterialPreviewContentPort {
    MaterialPreviewImage render(StoredArtifact pageImage);
}
