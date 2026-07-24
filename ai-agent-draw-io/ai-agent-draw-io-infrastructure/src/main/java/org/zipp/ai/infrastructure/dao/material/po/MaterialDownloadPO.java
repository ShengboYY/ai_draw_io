package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

/** Internal exact-original identity; never crosses the download port. */
@Data
public class MaterialDownloadPO {
    private String displayName;
    private String detectedMime;
    private String contentSha256;
    private long byteSize;
    private String objectKey;
    private String objectVersionId;
}
