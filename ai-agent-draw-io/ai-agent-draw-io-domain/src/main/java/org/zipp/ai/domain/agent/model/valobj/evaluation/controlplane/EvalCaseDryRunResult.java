package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;

/** One deterministic Mode B qualification run. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseDryRunResult {
    private EvalHarnessResult result;
    private EvalCaseWorkingCopy workingCopy;
    private String initialCanvasXml;
    private String finalCanvasXml;
    private EvalTrace trace;
}
