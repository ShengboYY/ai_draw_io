package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps one canonical full canvas in ADK state while mutation tools return compact local patches.
 * Geometry and quality rules remain owned by {@link DrawioCanvasXmlToolkit}; this class only
 * canonicalizes the mutation result before the drawer reads its analysis and repair brief.
 */
public class DrawioMutationResultPostProcessor {

    public static final String DRAFT_DIAGRAM_STATE_KEY = "draft_diagram";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();

    public Map<String, Object> process(String toolName,
                                       Map<String, Object> args,
                                       Map<String, Object> response,
                                       Map<String, Object> state) {
        Map<String, Object> processed = response == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response);
        if (!DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName)
                || "tool_error".equals(stringValue(processed.get("type")))) {
            return processed;
        }

        String candidate = canonicalCandidate(args, processed, state);
        if (StringUtils.isBlank(candidate)) {
            return processed;
        }

        // Match the stream writer's deterministic safety pass so the drawer, persisted canvas,
        // and eventual VLM screenshot all refer to the same candidate.
        candidate = xmlToolkit.repairGeometryIfNeeded(xmlToolkit.autoRepair(candidate));
        CanvasAnalysis analysis = xmlToolkit.analyze(candidate);
        processed.put("analysis", OBJECT_MAPPER.convertValue(
                DrawioCanvasMcpService.CanvasAnalysisResponse.from(analysis), MAP_TYPE));
        processed.put("repairBrief", DrawioRepairBriefComposer.compose(analysis));
        if (state != null) {
            state.put(DRAFT_DIAGRAM_STATE_KEY, candidate);
        }
        return processed;
    }

    private String canonicalCandidate(Map<String, Object> args,
                                      Map<String, Object> response,
                                      Map<String, Object> state) {
        String completeContent = stringValue(response.get("content"));
        if (StringUtils.isNotBlank(completeContent)) {
            return xmlToolkit.toGraphModel(completeContent);
        }

        String cells = stringValue(response.get("cells"));
        if (StringUtils.isBlank(cells)) {
            return "";
        }
        String currentDraft = state == null ? "" : stringValue(state.get(DRAFT_DIAGRAM_STATE_KEY));
        String argumentXml = args == null ? "" : stringValue(args.get("xml"));
        String base = StringUtils.defaultIfBlank(currentDraft, argumentXml);
        return StringUtils.isBlank(base) ? "" : xmlToolkit.replaceCells(base, cells);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
