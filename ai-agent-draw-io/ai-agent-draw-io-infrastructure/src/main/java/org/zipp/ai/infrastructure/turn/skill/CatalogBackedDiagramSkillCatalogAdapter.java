package org.zipp.ai.infrastructure.turn.skill;

import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogPort;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

/** Reuses the legacy catalog registry without exposing its MCP/session-bound loading tool to V2. */
@Component
public final class CatalogBackedDiagramSkillCatalogAdapter implements DiagramSkillCatalogPort {

    private final SkillCatalogService catalog;

    public CatalogBackedDiagramSkillCatalogAdapter(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public DiagramSkillCatalogSnapshot snapshot(String ownerKey) {
        return DiagramSkillCatalogProjection.snapshot(catalog.runtimeCatalog(ownerKey));
    }
}
