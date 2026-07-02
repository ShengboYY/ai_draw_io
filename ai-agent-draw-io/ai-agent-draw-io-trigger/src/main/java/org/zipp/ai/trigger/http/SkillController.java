package org.zipp.ai.trigger.http;

import lombok.extern.slf4j.Slf4j;
import org.zipp.ai.api.dto.SkillDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillManagementService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/** Management API for user/platform skills (create, list, delete). */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class SkillController {

    private static final String SUCCESS = "0000";
    private static final String FAILURE = "0001";

    @Resource
    private SkillManagementService skillManagementService;

    @Resource
    private org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService skillCatalogService;

    /** Shared secret required to create PUBLIC (platform-wide) skills. Empty = PUBLIC via API disabled. */
    @Value("${SKILL_ADMIN_TOKEN:}")
    private String skillAdminToken;

    /** Create or update a skill (upsert by owner + name). PUBLIC requires a valid admin token. */
    @PostMapping("skills")
    public Response<Void> saveSkill(@RequestBody SkillDTO request,
                                    @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        String ownerId = WorkspaceIds.resolve(request == null ? null : request.getUserId());
        if ("PUBLIC".equalsIgnoreCase(request.getVisibility()) && !isAdmin(adminToken)) {
            return Response.<Void>builder().code(FAILURE)
                    .info("creating PUBLIC skills requires a valid admin token").build();
        }
        try {
            skillManagementService.save(
                    ownerId, request.getName(), request.getDescription(),
                    request.getCategory(), request.getBody(), request.getVisibility());
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder().code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("保存技能失败", e);
            return Response.<Void>builder().code(FAILURE).info("保存技能失败").build();
        }
    }

    /** Selectable skill catalog (built-in + public + the user's private) for the slash-command picker. */
    @GetMapping("skills/catalog")
    public Response<List<SkillDTO>> skillCatalog(@RequestParam(value = "userId", required = false) String userId) {
        try {
            String ownerId = WorkspaceIds.resolve(userId);
            List<SkillDTO> skills = skillCatalogService.selectableSkills(ownerId).stream().map(info -> {
                SkillDTO dto = new SkillDTO();
                dto.setName(info.name());
                dto.setDescription(info.description());
                dto.setCategory(info.category());
                return dto;
            }).toList();
            return Response.<List<SkillDTO>>builder().code(SUCCESS).info("成功").data(skills).build();
        } catch (Exception e) {
            log.error("查询技能目录失败", e);
            return Response.<List<SkillDTO>>builder().code(FAILURE).info("查询技能目录失败").build();
        }
    }

    /** List skills visible to a user (platform public + the user's private). */
    @GetMapping("skills")
    public Response<List<SkillDTO>> listSkills(@RequestParam(value = "userId", required = false) String userId) {
        try {
            String ownerId = WorkspaceIds.resolve(userId);
            List<SkillDTO> skills = skillManagementService.list(ownerId).stream()
                    .map(this::toDTO).toList();
            return Response.<List<SkillDTO>>builder().code(SUCCESS).info("成功").data(skills).build();
        } catch (Exception e) {
            log.error("查询技能失败", e);
            return Response.<List<SkillDTO>>builder().code(FAILURE).info("查询技能失败").build();
        }
    }

    /** Delete a user's own private skill by name. */
    @DeleteMapping("skills")
    public Response<Void> deleteSkill(@RequestParam(value = "userId", required = false) String userId,
                                      @RequestParam("name") String name) {
        try {
            String ownerId = WorkspaceIds.resolve(userId);
            skillManagementService.delete(ownerId, name);
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder().code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("删除技能失败", e);
            return Response.<Void>builder().code(FAILURE).info("删除技能失败").build();
        }
    }

    private boolean isAdmin(String adminToken) {
        return StringUtils.hasText(skillAdminToken) && skillAdminToken.equals(adminToken);
    }

    private SkillDTO toDTO(SkillStore.StoredSkill skill) {
        SkillDTO dto = new SkillDTO();
        dto.setUserId(skill.ownerId());
        dto.setName(skill.name());
        dto.setDescription(skill.description());
        dto.setCategory(skill.category());
        dto.setBody(skill.body());
        dto.setVisibility(skill.visibility() == null ? null : skill.visibility().name());
        return dto;
    }
}
