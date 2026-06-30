package org.zipp.ai.domain.agent.service.armory.matter.skills;

import java.util.List;

/**
 * Persistence port for user/platform skills. Built-in skills ship in the jar (classpath); these are
 * the dynamic ones: platform-provided public skills and per-user private skills, stored so they can be
 * added/edited/evolved at runtime. Implemented in the infrastructure layer (DB-backed).
 */
public interface SkillStore {

    enum Visibility {
        PUBLIC,
        PRIVATE
    }

    record StoredSkill(
            String ownerId,
            String name,
            String description,
            String category,
            String body,
            Visibility visibility,
            boolean enabled) {
    }

    /** Platform-wide public skills available to everyone. */
    List<StoredSkill> listPublic();

    /** A single user's private skills. */
    List<StoredSkill> listByOwner(String ownerId);

    /** Insert or update a skill by (ownerId, name). For the future management/CRUD entry point. */
    void upsert(StoredSkill skill);

    /** Insert a skill only if (ownerId, name) is absent; never overwrites. Used for built-in seeding. */
    void seedIfAbsent(StoredSkill skill);

    /** Remove a user's private skill by name. */
    void delete(String ownerId, String name);
}
