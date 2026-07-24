package org.zipp.ai.domain.chartbook.service;

import org.zipp.ai.domain.chartbook.model.valobj.AddChartbookFileCommand;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookFileResult;

/** Deep module for Chartbook file lifecycle and scope changes. */
public interface ChartbookFileModule {
    ChartbookFileResult add(AddChartbookFileCommand command);
}
