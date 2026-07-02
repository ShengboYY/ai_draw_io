package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DefaultCurrentOwnerResolver implements ICurrentOwnerResolver {

    private static final Pattern ANONYMOUS_WORKSPACE_ID = Pattern.compile(
            "^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Override
    public Optional<ResolvedOwner> resolve(OwnerResolutionCommand command) {
        String ownerId = normalize(command == null ? null : command.getWorkspaceId());
        if (ownerId == null) {
            return Optional.empty();
        }
        // Issue #1 only supports anonymous owners; authenticated users plug into this seam later.
        return Optional.of(ResolvedOwner.anonymous(ownerId));
    }

    private String normalize(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        String value = workspaceId.trim().toLowerCase(Locale.ROOT);
        return ANONYMOUS_WORKSPACE_ID.matcher(value).matches() ? value : null;
    }
}
