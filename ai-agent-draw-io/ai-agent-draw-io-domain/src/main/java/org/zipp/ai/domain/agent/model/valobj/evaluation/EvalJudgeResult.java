package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EvalJudgeResult {
    private boolean passed;
    private boolean available;
    private double score;
    private int criticalIssues;
    private int majorIssues;
    private String confidence;
    private String judgeVersion;
    private List<String> evidence;
}
