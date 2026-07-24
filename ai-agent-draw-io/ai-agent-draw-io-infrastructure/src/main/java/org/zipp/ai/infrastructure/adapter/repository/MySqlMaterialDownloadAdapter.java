package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialDownloadFile;
import org.zipp.ai.domain.material.port.MaterialDownloadPort;
import org.zipp.ai.infrastructure.dao.material.IMaterialCatalogMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialDownloadPO;

import java.util.Objects;
import java.util.Optional;

/** Reads an exact owner-fenced original and strips all storage identity at the port boundary. */
@Repository
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class MySqlMaterialDownloadAdapter implements MaterialDownloadPort {
    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024;

    private final IMaterialCatalogMapper mapper;
    private final RevisionArtifactPort artifacts;

    public MySqlMaterialDownloadAdapter(IMaterialCatalogMapper mapper,
            @Qualifier("materialDownloadRevisionArtifactPort") RevisionArtifactPort artifacts) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public Optional<MaterialDownloadFile> find(CatalogOwner owner, String materialId, String versionId) {
        MaterialDownloadPO source = mapper.selectOwnedDownload(
                owner.ownerType().name(), owner.ownerKey(), materialId, versionId);
        if (source == null || source.getObjectKey() == null || source.getObjectVersionId() == null
                || source.getByteSize() < 1 || source.getByteSize() > MAX_DOWNLOAD_BYTES) {
            return Optional.empty();
        }
        StoredArtifact exact = new StoredArtifact(source.getObjectKey(), source.getObjectVersionId(),
                source.getContentSha256(), source.getByteSize(), source.getDetectedMime());
        return Optional.of(new MaterialDownloadFile(source.getDisplayName(), source.getDetectedMime(),
                artifacts.read(exact, MAX_DOWNLOAD_BYTES)));
    }
}
