package org.zipp.ai.infrastructure.dao.po.evaluation;
import lombok.Data;
import java.util.Date;
@Data
public class EvalCaseLineagePO { private String promotionId; private String caseId; private String datasetVersion; private String reviewer; private Date approvedAt; private String sanitizerVersion; private String origin; }
