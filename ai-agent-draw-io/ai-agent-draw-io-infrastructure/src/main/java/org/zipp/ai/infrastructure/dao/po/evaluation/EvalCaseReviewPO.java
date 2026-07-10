package org.zipp.ai.infrastructure.dao.po.evaluation;
import lombok.Data;
import java.util.Date;
@Data
public class EvalCaseReviewPO { private String id; private String candidateId; private String reviewer; private String decision; private String reason; private Date reviewedAt; }
