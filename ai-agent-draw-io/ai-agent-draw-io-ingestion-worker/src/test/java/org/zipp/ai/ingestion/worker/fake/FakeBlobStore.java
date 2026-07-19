package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.port.BlobStorePort;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeBlobStore implements BlobStorePort {
    private final Map<String, MaterialObject> objects = new ConcurrentHashMap<>();

    @Override
    public void put(MaterialObject object) {
        objects.put(object.objectKey(), new MaterialObject(object.objectKey(), object.contentSha256(), object.content()));
    }

    @Override
    public Optional<MaterialObject> get(String objectKey) {
        MaterialObject object = objects.get(objectKey);
        return object == null ? Optional.empty()
                : Optional.of(new MaterialObject(object.objectKey(), object.contentSha256(), object.content()));
    }

    @Override
    public boolean delete(String objectKey) {
        return objects.remove(objectKey) != null;
    }
}
