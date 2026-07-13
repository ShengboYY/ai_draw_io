package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;

import java.time.Clock;
import java.util.UUID;

/** Runs a validated case through the real deterministic Mode B chain. */
@Service
public class EvalCaseDryRunService {
    private static final String VERSION = "mode-b-dry-run-v1";
    private final EvalCaseWorkingCopyService workingCopies;
    private final IEvalCaseEvidenceStore evidenceStore;
    private final EvalBatchRunner.ExecutionFactory executionFactory;
    private final Clock clock;

    public EvalCaseDryRunService(EvalCaseWorkingCopyService workingCopies,
                                 IEvalCaseEvidenceStore evidenceStore) {
        this(workingCopies, evidenceStore, new ModeBReplayExecutionFactory(), Clock.systemUTC());
    }

    public EvalCaseDryRunService(EvalCaseWorkingCopyService workingCopies,
                                 IEvalCaseEvidenceStore evidenceStore,
                                 EvalBatchRunner.ExecutionFactory executionFactory, Clock clock) {
        this.workingCopies = workingCopies;
        this.evidenceStore = evidenceStore;
        this.executionFactory = executionFactory;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCaseDryRunResult run(String id, String actor, EvalAdminRole role) {
        EvalCaseWorkingCopy running = workingCopies.transition(id, EvalCaseWorkingCopyStatus.DRY_RUNNING, actor, role);
        EvalHarnessResult result;
        EvalExecution execution = null;
        try {
            execution = executionFactory.create(running.getDefinition());
            result = new DefaultEvalHarness().evaluate(execution);
        } catch (Exception e) {
            result = EvalHarnessResult.builder().caseId(running.getCaseId()).caseVersion(running.getCaseVersion())
                    .status(EvalHarnessResult.Status.ERROR).passed(false).errorClass(e.getClass().getName())
                    .errorMessage(e.getMessage()).build();
        }
        boolean passed = result.getStatus() == EvalHarnessResult.Status.PASS;
        EvalCaseWorkingCopy completed = workingCopies.transition(id,
                passed ? EvalCaseWorkingCopyStatus.DRY_RUN_PASSED : EvalCaseWorkingCopyStatus.DRY_RUN_FAILED,
                actor, role);
        evidenceStore.insert(EvalCaseEvidence.builder().id("ece_" + UUID.randomUUID())
                .workingCopyId(id).workingCopyRevision(completed.getRevision()).type(EvalCaseEvidenceType.DRY_RUN)
                .status(result.getStatus().name()).payloadJson(JSON.toJSONString(result)).componentVersion(VERSION)
                .createdAt(clock.instant()).build());
        return EvalCaseDryRunResult.builder().result(result).workingCopy(completed)
                .initialCanvasXml(execution == null ? null : execution.getInitialCanvasXml())
                .finalCanvasXml(execution == null ? null : execution.getFinalCanvasXml())
                .trace(execution == null ? null : execution.getTrace()).build();
    }
}
