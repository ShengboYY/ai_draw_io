package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** Non-sensitive readiness metadata; never contains provider secrets or sequestered Case bodies. */
@Value
@Builder
public class EvalLiveRunReadiness {
    boolean providerCredentialReady;
    boolean judgeCalibrationApproved;
    String calibrationVersion;
    String judgeVersion;
    int sequesteredCaseCount;
    int minimumSequesteredCases;
}
