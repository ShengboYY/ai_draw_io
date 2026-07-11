package org.zipp.ai.domain.agent.service.evaluation;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalStatisticalReport;

import java.util.ArrayList;
import java.util.List;

/** Combines deterministic hard gates and statistical relative gates into a three-state decision. */
public class EvalReleaseGateService {
    public Decision evaluate(Input input) {
        List<String> reasons = new ArrayList<>();
        boolean hardFailure = input.caseResults().stream().anyMatch(result ->
                result.result().getStatus() == EvalHarnessResult.Status.FAIL);
        if (hardFailure) {
            input.caseResults().stream().filter(result -> result.result().getStatus() == EvalHarnessResult.Status.FAIL)
                    .forEach(result -> reasons.add("hard gate failed: " + result.caseId() + " (" + result.risk() + ")"));
            return new Decision(Outcome.BLOCK, reasons);
        }
        boolean unscorable = input.caseResults().stream().anyMatch(result ->
                result.result().getStatus() == EvalHarnessResult.Status.ERROR
                        || result.result().getStatus() == EvalHarnessResult.Status.UNAVAILABLE);
        if (unscorable) reasons.add("deterministic suite contains ERROR/UNAVAILABLE");
        if (input.statisticalReport() == null || input.statisticalReport().getDecision() != EvalStatisticalReport.Decision.READY) {
            reasons.add("live statistical report is NO_DECISION or missing");
        }
        if (input.comparison() == null || input.comparison().getDecision() != EvalStatisticalReport.Decision.READY) {
            reasons.add("baseline comparison is NO_DECISION or missing");
        }
        if (input.judgeRequired() && (input.calibration() == null || !input.calibration().isApproved())) {
            reasons.add("required Judge calibration is not approved");
        }
        if (input.sequesteredCaseCount() < input.minimumSequesteredCases()) {
            reasons.add("sequestered set is below the approved minimum");
        }
        if (!reasons.isEmpty()) return new Decision(Outcome.NO_DECISION, reasons);
        if (input.comparison().isBlocked()) {
            reasons.add("paired regression CI crossed the approved threshold");
            return new Decision(Outcome.BLOCK, reasons);
        }
        return new Decision(Outcome.PASS, List.of("all hard, availability, calibration, sequestered, and relative gates passed"));
    }

    public enum Outcome { PASS, BLOCK, NO_DECISION }
    public record CaseResult(String caseId, String risk, EvalHarnessResult result) { }
    public record Input(List<CaseResult> caseResults, EvalStatisticalReport statisticalReport,
                        EvalStatisticalReport.Comparison comparison, JudgeCalibrationService.Report calibration,
                        boolean judgeRequired, int sequesteredCaseCount, int minimumSequesteredCases) {
        public Input {
            caseResults = caseResults == null ? List.of() : caseResults;
        }
    }
    public record Decision(Outcome outcome, List<String> reasons) { }
}
