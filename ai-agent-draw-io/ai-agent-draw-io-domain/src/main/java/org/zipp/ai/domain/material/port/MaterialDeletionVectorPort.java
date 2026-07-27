package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.valobj.MaterialVectorLocation;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt;

import java.util.List;

public interface MaterialDeletionVectorPort {
    MaterialDeletionReceipt delete(List<MaterialVectorLocation> vectors);
}
