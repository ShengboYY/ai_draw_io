package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;

import java.util.List;

/** Prevents an uncalibrated Judge version from silently participating in scored evaluation. */
public class CalibratedEvalJudge implements IEvalJudge {
    private final IEvalJudge delegate;
    private final JudgeCalibrationService.Report calibration;

    public CalibratedEvalJudge(IEvalJudge delegate, JudgeCalibrationService.Report calibration) {
        this.delegate = delegate; this.calibration = calibration;
    }

    @Override
    public EvalJudgeResult judge(JudgeInput input) {
        if (calibration == null || !calibration.isApproved()) {
            return EvalJudgeResult.builder().available(false).passed(false)
                    .judgeVersion(calibration == null ? null : calibration.getJudgeVersion())
                    .evidence(List.of("Judge calibration is not approved.")).build();
        }
        EvalJudgeResult result = delegate.judge(input);
        if (result != null && !java.util.Objects.equals(calibration.getJudgeVersion(), result.getJudgeVersion())) {
            return EvalJudgeResult.builder().available(false).passed(false).judgeVersion(result.getJudgeVersion())
                    .evidence(List.of("Judge version does not match approved calibration.")).build();
        }
        return result;
    }

    @Override
    public boolean isCalibrated() {
        return calibration != null && calibration.isApproved();
    }

    @Override
    public boolean isCalibrated(JudgeInput input) {
        return isCalibrated() && delegate != null;
    }
}
