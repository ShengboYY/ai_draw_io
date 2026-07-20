package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.material.model.valobj.MaterialObjectVersion;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.List;
import java.util.Objects;

/** Deletes only the exact immutable S3 versions recorded by the material manifests. */
public final class S3MaterialDeletionAdapter implements MaterialDeletionObjectPort {
    public static final String MATERIALS_BUCKET = "MATERIALS";
    private final S3Client s3;
    private final String bucket;

    public S3MaterialDeletionAdapter(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("materials bucket is required");
        this.bucket = bucket.trim();
    }

    @Override
    public MaterialDeletionReceipt delete(List<MaterialObjectVersion> objects) {
        java.util.ArrayList<String> requestIds = new java.util.ArrayList<>();
        for (MaterialObjectVersion object : List.copyOf(objects)) {
            String exactBucket = MATERIALS_BUCKET.equals(object.bucket()) ? bucket : object.bucket();
            var response = s3.deleteObject(DeleteObjectRequest.builder().bucket(exactBucket)
                    .key(object.objectKey()).versionId(object.objectVersionId()).build());
            String requestId = response.responseMetadata().requestId();
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalStateException("S3 deletion response has no request id");
            }
            requestIds.add(requestId);
        }
        return MaterialDeletionReceipt.from(objects.size(), requestIds);
    }
}
