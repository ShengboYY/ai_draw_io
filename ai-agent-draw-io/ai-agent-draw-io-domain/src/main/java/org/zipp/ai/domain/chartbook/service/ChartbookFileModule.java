package org.zipp.ai.domain.chartbook.service;

import org.zipp.ai.domain.chartbook.model.valobj.AddChartbookFileCommand;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookFileResult;
import org.zipp.ai.domain.chartbook.model.valobj.RemoveChartbookFileCommand;

/** Deep module for Chartbook file lifecycle and scope changes. */
public interface ChartbookFileModule {
    ChartbookFileResult add(AddChartbookFileCommand command);

    default ChartbookFileResult remove(RemoveChartbookFileCommand command) {
        throw new UnsupportedOperationException("chartbook file removal is unavailable");
    }
}
