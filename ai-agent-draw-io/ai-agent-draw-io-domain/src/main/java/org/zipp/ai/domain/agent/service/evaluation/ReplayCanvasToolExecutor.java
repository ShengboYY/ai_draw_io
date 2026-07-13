package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

/** Shared deterministic tool boundary for Full Agent and drawing-only replay. */
final class ReplayCanvasToolExecutor {
    String execute(EvalCaseDefinition.ReplayToolCall call, String currentXml) {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        String toolName = require(call.getName(), "replay.toolCalls[].name");
        if (DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(toolName)) {
            DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
            request.setMode(require(call.getMode(), "replay.toolCalls[].mode"));
            request.setXml(call.getXml() == null ? currentXml : call.getXml());
            request.setCells(require(call.getCells(), "replay.toolCalls[].cells"));
            DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);
            assertRepairSignal(call, response.getRepairBrief());
            if (response.getContent() != null) return response.getContent();
            if (response.getCells() != null) return new DrawioCanvasXmlToolkit().replaceCells(currentXml, response.getCells());
            throw new IllegalStateException("modify_diagram returned no canvas artifact");
        }
        if (DrawioCanvasToolNames.CREATE_DIAGRAM.equals(toolName)) {
            DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
            request.setXml(require(call.getXml() == null ? call.getCells() : call.getXml(),
                    "replay.toolCalls[].xml or cells"));
            return service.createDiagram(request).getContent();
        }
        throw new IllegalArgumentException("Unsupported replay tool: " + toolName);
    }

    private void assertRepairSignal(EvalCaseDefinition.ReplayToolCall call, String repairBrief) {
        String expected = call.getExpectedRepairContains();
        if (expected != null && (repairBrief == null || !repairBrief.contains(expected))) {
            throw new IllegalStateException("Expected repair signal containing: " + expected);
        }
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
