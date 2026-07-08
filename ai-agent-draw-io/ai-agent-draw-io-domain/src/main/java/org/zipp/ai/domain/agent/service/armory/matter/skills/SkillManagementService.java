package org.zipp.ai.domain.agent.service.armory.matter.skills;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Create/list/delete for user-managed skills, with validation guards for untrusted user content.
 * Backed by the optional {@link SkillStore} (DB). Built-in jar skills are not managed here.
 */
@Service
public class SkillManagementService {

    public static final int MAX_BODY_CHARS = 20_000;
    public static final int MAX_DESCRIPTION_CHARS = 2_000;
    private static final int MAX_NAME_CHARS = 128;
    private static final String DEFAULT_CATEGORY = "drawio-design";
    // Safe skill names: letters/digits/_/- only (also avoids path/separator surprises).
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    @Autowired(required = false)
    private SkillStore skillStore;

    @Autowired
    private SkillCatalogService skillCatalogService;

    /** Insert or update a skill (keyed by owner + name). Throws IllegalArgumentException on invalid input. */
    public void save(String ownerId, String name, String description, String category, String body, String visibility) {
        requireStore();

        SkillStore.Visibility vis = parseVisibility(visibility);
        String trimmedName = name == null ? "" : name.trim();
        if (!NAME_PATTERN.matcher(trimmedName).matches()) {
            throw new IllegalArgumentException("name must be 1-" + MAX_NAME_CHARS + " chars of letters, digits, '_' or '-'");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("body exceeds " + MAX_BODY_CHARS + " characters");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description exceeds " + MAX_DESCRIPTION_CHARS + " characters");
        }

        String owner = vis == SkillStore.Visibility.PUBLIC ? "" : safeOwner(ownerId);
        if (vis == SkillStore.Visibility.PRIVATE && owner.isEmpty()) {
            throw new IllegalArgumentException("ownerId is required for a private skill");
        }
        String cat = (category == null || category.isBlank()) ? DEFAULT_CATEGORY : category.trim();
        List<String> validationErrors = skillCatalogService.validateManagedSkill(
                trimmedName, description == null ? "" : description.trim(), cat, body);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException("invalid skill: " + String.join("; ", validationErrors));
        }

        skillStore.upsert(new SkillStore.StoredSkill(
                owner, trimmedName,
                description == null ? "" : description.trim(),
                cat, body, vis, true));
        skillCatalogService.invalidateCache();
    }

    /** Skills visible to a user for management: platform public + that user's private. */
    public List<SkillStore.StoredSkill> list(String ownerId) {
        requireStore();
        List<SkillStore.StoredSkill> all = new ArrayList<>(skillStore.listPublic());
        if (ownerId != null && !ownerId.isBlank()) {
            all.addAll(skillStore.listByOwner(ownerId));
        }
        return all;
    }

    /** Delete a user's own private skill by name. */
    public void delete(String ownerId, String name) {
        requireStore();
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        skillStore.delete(ownerId.trim(), name.trim());
    }

    private void requireStore() {
        if (skillStore == null) {
            throw new IllegalStateException("Skill store is not available (database not configured)");
        }
    }

    private String safeOwner(String ownerId) {
        return ownerId == null ? "" : ownerId.trim();
    }

    private SkillStore.Visibility parseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return SkillStore.Visibility.PRIVATE;
        }
        try {
            return SkillStore.Visibility.valueOf(visibility.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("visibility must be PUBLIC or PRIVATE");
        }
    }
}
