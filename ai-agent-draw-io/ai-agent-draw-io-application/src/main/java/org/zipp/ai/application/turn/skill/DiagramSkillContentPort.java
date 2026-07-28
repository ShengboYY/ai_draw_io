package org.zipp.ai.application.turn.skill;

/** Loads the exact skill contents already authorized and pinned in a Plain plan. */
@FunctionalInterface
public interface DiagramSkillContentPort {

    DiagramSkillBundle load(String ownerKey, ResolvedDiagramSkillSelection selection);
}
