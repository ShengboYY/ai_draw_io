package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;

import java.util.Optional;

public interface BlobStorePort {
    void put(MaterialObject object);
    Optional<MaterialObject> get(String objectKey);
    boolean delete(String objectKey);
}
