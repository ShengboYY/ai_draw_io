package org.zipp.ai.trigger.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalLiveRunReadiness;
import org.zipp.ai.domain.agent.service.evaluation.CalibratedEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.ChatEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.JudgeCalibrationService;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalLiveRunSupport;

import java.util.Objects;

/** Production wiring for live execution; configuration contains readiness metadata, never provider secrets. */
@Component
public class ProductionEvalLiveRunSupport implements IEvalLiveRunSupport {
    private final ProductionLiveEvalAdapter execution;
    private final ChatEvalJudge rawJudge;
    private final boolean providerCredentialReady;
    private final boolean calibrationApproved;
    private final String calibrationVersion;
    private final String calibratedJudgeVersion;
    private final int sequesteredCaseCount;
    private final int minimumSequesteredCases;

    public ProductionEvalLiveRunSupport(ProductionLiveEvalAdapter execution, ChatEvalJudge rawJudge,
            @Value("${zipp.evaluation.live-enabled:false}") boolean providerCredentialReady,
            @Value("${zipp.evaluation.judge-calibration-approved:false}") boolean calibrationApproved,
            @Value("${zipp.evaluation.judge-calibration-version:unconfigured}") String calibrationVersion,
            @Value("${zipp.evaluation.judge-calibrated-version:unconfigured}") String calibratedJudgeVersion,
            @Value("${zipp.evaluation.sequestered-case-count:0}") int sequesteredCaseCount,
            @Value("${zipp.evaluation.minimum-sequestered-cases:20}") int minimumSequesteredCases) {
        this.execution = execution; this.rawJudge = rawJudge; this.providerCredentialReady = providerCredentialReady;
        this.calibrationApproved = calibrationApproved; this.calibrationVersion = calibrationVersion;
        this.calibratedJudgeVersion = calibratedJudgeVersion; this.sequesteredCaseCount = sequesteredCaseCount;
        this.minimumSequesteredCases = minimumSequesteredCases;
    }

    @Override public LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef) { return execution; }

    @Override public IEvalJudge judge() {
        return new CalibratedEvalJudge(rawJudge, JudgeCalibrationService.Report.builder()
                .approved(calibrationReady()).judgeVersion(calibratedJudgeVersion).build());
    }

    @Override public EvalLiveRunReadiness readiness() {
        return EvalLiveRunReadiness.builder().providerCredentialReady(providerCredentialReady)
                .judgeCalibrationApproved(calibrationReady()).calibrationVersion(calibrationVersion)
                .judgeVersion(rawJudge.version(null)).sequesteredCaseCount(sequesteredCaseCount)
                .minimumSequesteredCases(minimumSequesteredCases).build();
    }

    private boolean calibrationReady() {
        return calibrationApproved && Objects.equals(calibratedJudgeVersion, rawJudge.version(null));
    }
}
