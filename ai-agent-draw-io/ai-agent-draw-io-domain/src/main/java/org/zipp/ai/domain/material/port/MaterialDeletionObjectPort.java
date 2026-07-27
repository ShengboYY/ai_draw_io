package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.valobj.MaterialObjectVersion;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt;

import java.util.List;

public interface MaterialDeletionObjectPort {
    MaterialDeletionReceipt delete(List<MaterialObjectVersion> objects);
}
