package org.zipp.ai.domain.agent.service.armory.matter.skills;

import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DrawioSkillAccessContext {

    private static final ThreadLocal<SkillAccess> CURRENT = new ThreadLocal<>();
    private static final ConcurrentMap<String, SkillAccess> SESSION_ACCESS = new ConcurrentHashMap<>();

    private DrawioSkillAccessContext() {
    }

    public static Scope bind(Collection<String> allowedSkillNames) {
        return bind(new SkillAccess(normalize(allowedSkillNames)));
    }

    public static Scope bind(SkillAccess access) {
        SkillAccess previous = CURRENT.get();
        CURRENT.set(access);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<SkillAccess> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<SkillAccess> resolve(String sessionId) {
        SkillAccess current = CURRENT.get();
        if (current != null) {
            return Optional.of(current);
        }
        if (StringUtils.isBlank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(SESSION_ACCESS.get(sessionId));
    }

    public static void bindSession(String sessionId, Collection<String> allowedSkillNames) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        SESSION_ACCESS.put(sessionId, new SkillAccess(normalize(allowedSkillNames)));
    }

    public static void clearSession(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            SESSION_ACCESS.remove(sessionId);
        }
    }

    private static Set<String> normalize(Collection<String> skillNames) {
        Set<String> normalized = new LinkedHashSet<>();
        if (skillNames == null) {
            return Set.of();
        }
        for (String skillName : skillNames) {
            String selected = StringUtils.trimToNull(skillName);
            if (selected != null && !"none".equalsIgnoreCase(selected)) {
                normalized.add(selected);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record SkillAccess(Set<String> allowedSkillNames) {
        public boolean allows(String skillName) {
            return StringUtils.isNotBlank(skillName)
                    && allowedSkillNames != null
                    && allowedSkillNames.contains(skillName.trim());
        }
    }
}
