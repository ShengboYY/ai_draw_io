package org.zipp.ai.api.dto;

import lombok.Data;

/** Request/response shape for the skill management API. */
@Data
public class SkillDTO {
    /** Owner user id. For PUBLIC skills this is ignored (stored as empty). */
    private String userId;
    private String name;
    private String description;
    private String category;
    private String body;
    private String visibility; // PUBLIC | PRIVATE (default PRIVATE)
}
