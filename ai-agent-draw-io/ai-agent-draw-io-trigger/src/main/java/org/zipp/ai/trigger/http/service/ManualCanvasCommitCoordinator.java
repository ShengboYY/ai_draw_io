package org.zipp.ai.trigger.http.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationDecision;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.service.ManualCitationReconciler;

/** Keeps the ordinary canvas save and manual provenance reconciliation in one transaction. */
@Service
@ConditionalOnProperty(name = {"app.material-rag.enabled", "app.material-lifecycle.enabled"}, havingValue = "true")
public class ManualCanvasCommitCoordinator {
    private final CanvasMutationGate mutationGate;
    private final ManualCitationReconciler reconciler;

    public ManualCanvasCommitCoordinator(CanvasMutationGate mutationGate, ManualCitationReconciler reconciler) {
        this.mutationGate = mutationGate;
        this.reconciler = reconciler;
    }

    @Transactional
    public CanvasMutationDecision commit(CanvasMutationCommand command) {
        CanvasMutationDecision assessment = mutationGate.assess(command);
        if (assessment.status() != org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus.ACCEPTED
                && assessment.status() != org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationStatus.ACCEPTED_WITH_NOTES) {
            return assessment;
        }
        CanvasMutationCommand secured = reconciler.rebuildServerMetadata(command, assessment);
        CanvasMutationDecision decision = mutationGate.evaluate(secured);
        reconciler.reconcile(decision);
        return decision;
    }
}
