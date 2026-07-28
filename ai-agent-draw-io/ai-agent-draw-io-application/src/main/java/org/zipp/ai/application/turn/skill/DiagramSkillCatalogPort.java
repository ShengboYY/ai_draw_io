package org.zipp.ai.application.turn.skill;

/** Owner-scoped read port for the V2 router; implementations must return one immutable snapshot. */
public interface DiagramSkillCatalogPort {

    DiagramSkillCatalogSnapshot snapshot(String ownerKey);

    static DiagramSkillCatalogPort empty() {
        return ignored -> DiagramSkillCatalogSnapshot.empty();
    }
}
