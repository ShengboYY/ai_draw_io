package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.UploadAdmissionRequest;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadLimits;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.service.UploadAdmissionPolicy;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadAdmissionPolicyTest {

    @Test
    void anonymousUploadRequiresFeatureFlagTemporaryConversationAndQuotaHeadroom() {
        UploadAdmissionPolicy disabled = new UploadAdmissionPolicy(UploadLimits.defaults(false));
        UploadAdmissionRequest request = anonymousPdf(1024L, 0, 0, 0, 0);

        assertRejected(UploadErrorCode.UPLOAD_DISABLED, () -> disabled.validate(request));

        UploadAdmissionPolicy enabled = new UploadAdmissionPolicy(UploadLimits.defaults(true));
        UploadAdmissionRequest retained = new UploadAdmissionRequest(
                OwnerType.ANONYMOUS,
                new UploadTarget(MaterialScopeType.LIBRARY, "library", RetentionClass.RETAINED),
                "application/pdf", 1024L, 0L, 0, 0, 0, 0, 1);
        assertRejected(UploadErrorCode.ANONYMOUS_SCOPE_FORBIDDEN, () -> enabled.validate(retained));
        assertRejected(UploadErrorCode.WORKSPACE_FILE_LIMIT,
                () -> enabled.validate(anonymousPdf(1024L, 3, 0, 0, 0)));
        assertRejected(UploadErrorCode.PROCESSING_CONCURRENCY_LIMIT,
                () -> enabled.validate(anonymousPdf(1024L, 0, 1, 0, 0)));
        assertRejected(UploadErrorCode.WORKSPACE_RATE_LIMIT,
                () -> enabled.validate(anonymousPdf(1024L, 0, 0, 10, 0)));
        assertRejected(UploadErrorCode.IP_RATE_LIMIT,
                () -> enabled.validate(anonymousPdf(1024L, 0, 0, 0, 30)));
    }

    private UploadAdmissionRequest anonymousPdf(long bytes, int activeFiles, int processing,
                                                 int workspaceHourly, int ipHourly) {
        return new UploadAdmissionRequest(
                OwnerType.ANONYMOUS,
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                "application/pdf", bytes, 0L, activeFiles, processing, workspaceHourly, ipHourly, 1);
    }

    private void assertRejected(UploadErrorCode code, Runnable operation) {
        var error = assertThrows(IllegalArgumentException.class, operation::run);
        assertEquals(code.name(), error.getMessage());
    }
}
