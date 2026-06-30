package org.zipp.ai.infrastructure.adapter.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;
import org.zipp.ai.infrastructure.dao.ISkillMapper;
import org.zipp.ai.infrastructure.dao.po.SkillPO;

import javax.annotation.Resource;
import java.util.List;

/** DB-backed {@link SkillStore} (MyBatis). */
@Slf4j
@Repository
public class SkillRepository implements SkillStore {

    @Resource
    private ISkillMapper skillMapper;

    @Override
    public List<StoredSkill> listPublic() {
        return skillMapper.selectPublic().stream().map(this::toDomain).toList();
    }

    @Override
    public List<StoredSkill> listByOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return List.of();
        }
        return skillMapper.selectByOwner(ownerId).stream().map(this::toDomain).toList();
    }

    @Override
    public void upsert(StoredSkill skill) {
        SkillPO po = new SkillPO();
        po.setOwnerId(skill.ownerId() == null ? "" : skill.ownerId());
        po.setName(skill.name());
        po.setDescription(skill.description());
        po.setCategory(skill.category());
        po.setBody(skill.body());
        po.setVisibility(skill.visibility() == null ? Visibility.PRIVATE.name() : skill.visibility().name());
        po.setEnabled(skill.enabled());
        skillMapper.upsert(po);
    }

    @Override
    public void delete(String ownerId, String name) {
        skillMapper.deleteByOwnerAndName(ownerId == null ? "" : ownerId, name);
    }

    private StoredSkill toDomain(SkillPO po) {
        Visibility visibility = "PUBLIC".equalsIgnoreCase(po.getVisibility()) ? Visibility.PUBLIC : Visibility.PRIVATE;
        boolean enabled = po.getEnabled() == null || po.getEnabled();
        return new StoredSkill(po.getOwnerId(), po.getName(), po.getDescription(),
                po.getCategory(), po.getBody(), visibility, enabled);
    }
}
