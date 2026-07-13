package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;
import java.util.Date;

@Data
public class EvalGateDecisionPO {
    private String evalRunId; private String gateVersion; private String outcome; private String reasonsJson;
    private Date decidedAt; private Boolean overrideApproved; private String overrideReason; private String overriddenBy;
    private Date overriddenAt;
}
