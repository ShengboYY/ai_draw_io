package org.zipp.ai.trigger.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalLiveRunReadiness;
import org.zipp.ai.domain.agent.service.evaluation.CalibratedEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.ChatEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.JudgeCalibrationService;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.evaluation.RoutedEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalLiveRunSupport;
import org.zipp.ai.domain.agent.service.evaluation.visual.ChatVisualEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.visual.DrawioSvgRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.VisualEvalJudgeAdapter;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

import java.util.Objects;

/** Production wiring for live execution; configuration contains readiness metadata, never provider secrets. */
@Component
public class ProductionEvalLiveRunSupport implements IEvalLiveRunSupport {
    private final ProductionLiveEvalAdapter execution;
    private final ProductionRouterLiveEvalAdapter routerExecution;
    private final ProductionDrawingLiveEvalAdapter drawingExecution;
    private final ChatEvalJudge rawJudge;
    private final ChatVisualEvalJudge rawVisualJudge;
    private final boolean providerCredentialReady;
    private final boolean calibrationApproved;
    private final String calibrationVersion;
    private final String calibratedJudgeVersion;
    private final boolean visualCalibrationApproved;
    private final String visualCalibrationVersion;
    private final String calibratedVisualJudgeVersion;
    private final int sequesteredCaseCount;
    private final int minimumSequesteredCases;

    public ProductionEvalLiveRunSupport(ProductionLiveEvalAdapter execution,
            ProductionRouterLiveEvalAdapter routerExecution,
            ProductionDrawingLiveEvalAdapter drawingExecution, ChatEvalJudge rawJudge,
            ChatVisualEvalJudge rawVisualJudge,
            @Value("${zipp.evaluation.live-enabled:false}") boolean providerCredentialReady,
            @Value("${zipp.evaluation.judge-calibration-approved:false}") boolean calibrationApproved,
            @Value("${zipp.evaluation.judge-calibration-version:unconfigured}") String calibrationVersion,
            @Value("${zipp.evaluation.judge-calibrated-version:unconfigured}") String calibratedJudgeVersion,
            @Value("${zipp.evaluation.visual-judge-calibration-approved:false}") boolean visualCalibrationApproved,
            @Value("${zipp.evaluation.visual-judge-calibration-version:unconfigured}") String visualCalibrationVersion,
            @Value("${zipp.evaluation.visual-judge-calibrated-version:unconfigured}") String calibratedVisualJudgeVersion,
            @Value("${zipp.evaluation.sequestered-case-count:0}") int sequesteredCaseCount,
            @Value("${zipp.evaluation.minimum-sequestered-cases:20}") int minimumSequesteredCases) {
        this.execution = execution; this.routerExecution = routerExecution; this.drawingExecution = drawingExecution;
        this.rawJudge = rawJudge; this.rawVisualJudge = rawVisualJudge; this.providerCredentialReady = providerCredentialReady;
        this.calibrationApproved = calibrationApproved; this.calibrationVersion = calibrationVersion;
        this.calibratedJudgeVersion = calibratedJudgeVersion; this.sequesteredCaseCount = sequesteredCaseCount;
        this.visualCalibrationApproved = visualCalibrationApproved; this.visualCalibrationVersion = visualCalibrationVersion;
        this.calibratedVisualJudgeVersion = calibratedVisualJudgeVersion;
        this.minimumSequesteredCases = minimumSequesteredCases;
    }

    @Override public LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef) { return execution; }

    @Override
    public LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef,
            EvaluationTarget target) {
        return switch (target) {
            case FULL_AGENT -> execution;
            case INTENT_ROUTER -> routerExecution;
            case DRAWING_QUALITY -> drawingExecution;
        };
    }

    @Override public IEvalJudge judge() {
        IEvalJudge text = new CalibratedEvalJudge(rawJudge, JudgeCalibrationService.Report.builder()
                .approved(calibrationReady()).judgeVersion(calibratedJudgeVersion).build());
        IEvalJudge visual = new CalibratedEvalJudge(new VisualEvalJudgeAdapter(rawVisualJudge),
                JudgeCalibrationService.Report.builder().approved(visualCalibrationReady())
                        .judgeVersion(calibratedVisualJudgeVersion).build());
        return new RoutedEvalJudge(text, visual);
    }

    @Override public EvalLiveRunReadiness readiness() {
        return EvalLiveRunReadiness.builder().providerCredentialReady(providerCredentialReady)
                .judgeCalibrationApproved(calibrationReady()).calibrationVersion(calibrationVersion)
                .judgeVersion(rawJudge.version(null)).sequesteredCaseCount(sequesteredCaseCount)
                .visualJudgeCalibrationApproved(visualCalibrationReady()).visualCalibrationVersion(visualCalibrationVersion)
                .visualJudgeVersion(rawVisualJudge.version())
                .minimumSequesteredCases(minimumSequesteredCases).build();
    }

    @Override public IDiagramImageRenderer diagramRenderer() { return new DrawioSvgRenderer(); }

    private boolean calibrationReady() {
        return calibrationApproved && Objects.equals(calibratedJudgeVersion, rawJudge.version(null));
    }

    private boolean visualCalibrationReady() {
        return visualCalibrationApproved && Objects.equals(calibratedVisualJudgeVersion, rawVisualJudge.version());
    }
}
