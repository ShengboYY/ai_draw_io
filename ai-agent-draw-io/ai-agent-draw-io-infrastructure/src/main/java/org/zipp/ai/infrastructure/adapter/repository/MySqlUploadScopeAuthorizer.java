package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.infrastructure.dao.material.IUploadSessionMapper;

import java.util.Objects;

@Repository
public class MySqlUploadScopeAuthorizer implements UploadScopeAuthorizer {

    private final IUploadSessionMapper mapper;

    public MySqlUploadScopeAuthorizer(IUploadSessionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public boolean canUpload(OwnerType ownerType, String ownerKey, UploadTarget target, String contextDiagramId,
                             String newVersionOfMaterialId) {
        if (ownerType == null || isBlank(ownerKey) || target == null) {
            return false;
        }
        if (!isBlank(newVersionOfMaterialId)
                && mapper.countOwnedMaterial(ownerType.name(), ownerKey, newVersionOfMaterialId) != 1) {
            return false;
        }
        MaterialScopeType scope = target.scopeType();
        return switch (scope) {
            case LIBRARY -> ownerType == OwnerType.USER
                    && ("personal".equals(target.scopeKey()) || ownerKey.equals(target.scopeKey()));
            case DIAGRAM -> mapper.countOwnedDiagram(ownerKey, target.scopeKey()) == 1;
            case CHARTBOOK -> ownerType == OwnerType.USER
                    && mapper.countOwnedChartbook(ownerKey, target.scopeKey()) == 1;
            case CONVERSATION -> ownsOrBindsConversation(ownerKey, target.scopeKey(), contextDiagramId);
        };
    }

    private boolean ownsOrBindsConversation(String ownerKey, String conversationId, String diagramId) {
        if (mapper.countOwnedConversation(ownerKey, conversationId) > 0) {
            return true;
        }
        if (isBlank(diagramId) || mapper.countOwnedDiagram(ownerKey, diagramId) != 1) {
            return false;
        }
        // The first upload can establish the server-side session binding, but only through an
        // already owner-scoped diagram. INSERT IGNORE cannot steal an existing conversation ID.
        mapper.bindConversation(conversationId, diagramId, ownerKey);
        return mapper.countOwnedConversation(ownerKey, conversationId) > 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
