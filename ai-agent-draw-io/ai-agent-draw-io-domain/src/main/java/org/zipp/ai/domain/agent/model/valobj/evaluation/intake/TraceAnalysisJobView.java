package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import java.util.List;

/** Job plus item progress returned to the Trace Analysis workbench. */
public record TraceAnalysisJobView(TraceAnalysisJob job, List<TraceAnalysisItem> items) {
    public TraceAnalysisJobView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
