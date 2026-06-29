package org.zipp.ai.domain.agent.model.valobj.quality;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QualityIssue {

    private String category;

    private String type;

    private String severity;

    private String message;

    private String cellId;

}
