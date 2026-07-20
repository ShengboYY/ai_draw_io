package org.zipp.ai.api.dto;

import java.util.List;

public record RagReleaseDecisionDTO(String outcome, List<String> violations, String approvalIdentity) { }
