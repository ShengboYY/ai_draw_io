package org.zipp.ai.infrastructure.adapter.filesystem;

import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.BrowserPostPolicy;
import org.zipp.ai.domain.ingestion.port.UploadPolicySignerPort;

import java.util.Map;

public final class LocalUploadPolicySigner implements UploadPolicySignerPort {

    @Override
    public BrowserPostPolicy sign(UploadSession session) {
        java.util.Objects.requireNonNull(session, "session");
        return new BrowserPostPolicy(
                "/material-uploads/" + session.id() + "/content",
                Map.of("x-ai-upload-mode", "local"),
                session.policyExpiresAt());
    }
}
