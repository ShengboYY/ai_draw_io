package org.zipp.ai.domain.ingestion.service;

public interface UploadIdFactory {
    String nextUploadId();
    String nextObjectId();
    String nextJobId();
    String ownerPathToken(String ownerKey);
}
