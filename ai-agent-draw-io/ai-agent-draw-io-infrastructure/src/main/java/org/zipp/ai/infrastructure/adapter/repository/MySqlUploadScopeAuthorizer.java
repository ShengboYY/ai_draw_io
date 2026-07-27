package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.infrastructure.dao.material.IUploadSessionMapper;

import java.util.Objects;

@Repository
public class MySqlUploadScopeAuthorizer implements UploadScopeAuthorizer {

    private final IUploadSessionMapper mapper;
    private final MySqlConversationScopeKeyResolver conversationScopes;
    private final ConversationCatalogPort conversationCatalog;

    public MySqlUploadScopeAuthorizer(IUploadSessionMapper mapper) {
        this(mapper, null, null);
    }

    public MySqlUploadScopeAuthorizer(IUploadSessionMapper mapper,
                                      MySqlConversationScopeKeyResolver conversationScopes) {
        this(mapper, conversationScopes, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MySqlUploadScopeAuthorizer(IUploadSessionMapper mapper,
                                      MySqlConversationScopeKeyResolver conversationScopes,
                                      ConversationCatalogPort conversationCatalog) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.conversationScopes = conversationScopes;
        this.conversationCatalog = conversationCatalog;
    }

    @Override
    public UploadTarget canonicalTarget(OwnerType ownerType, String ownerKey, UploadTarget target,
                                        String contextDiagramId) {
        if (target == null || target.scopeType() != MaterialScopeType.CONVERSATION || conversationScopes == null) {
            return target;
        }
        AuthenticatedActor actor = new AuthenticatedActor(ownerKey, ownerKey);
        String canonical;
        try {
            canonical = conversationScopes.newWriteScopeKey(actor, target.scopeKey(), contextDiagramId);
        } catch (IllegalStateException unresolved) {
            canonical = provisionNewSessionAlias(actor, target.scopeKey(), contextDiagramId, unresolved).id();
        }
        return new UploadTarget(target.scopeType(), canonical, target.retentionClass());
    }

    private ConversationRef provisionNewSessionAlias(
            AuthenticatedActor actor,
            String rawReference,
            String diagramId,
            IllegalStateException unresolved
    ) {
        if (conversationCatalog == null || isBlank(diagramId)) {
            throw unresolved;
        }
        if ("CONVERSATION_DEFAULT_UNRESOLVED".equals(unresolved.getMessage())
                && "default".equals(rawReference)) {
            return conversationCatalog.findOrCreateDefault(actor, diagramId);
        }
        if (!"CONVERSATION_SCOPE_REFERENCE_UNRESOLVED".equals(unresolved.getMessage())
                || rawReference.startsWith("conv_")) {
            throw unresolved;
        }
        // Runtime sessions are accepted only after the catalog locks an active diagram
        // owned by this actor; canonical-looking stale IDs remain fail-closed.
        return conversationCatalog.findOrCreateDefaultForLegacySession(actor, rawReference, diagramId);
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
                    && MaterialScopeType.isPersonalLibraryKey(target.scopeKey(), ownerKey);
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
