package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for the {@code skill} table (user/platform skills). */
@Data
public class SkillPO {
    private Long id;
    private String ownerId;
    private String name;
    private String description;
    private String category;
    private String body;
    private String visibility; // PUBLIC | PRIVATE
    private Integer version;
    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
