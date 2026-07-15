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
        return processWithStatus(toolName, args, response, state).response();
    }

    public ProcessResult processWithStatus(String toolName,
                                           Map<String, Object> args,
                                           Map<String, Object> response,
                                           Map<String, Object> state) {
        Map<String, Object> processed = response == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response);
        if (!DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName)
                || "tool_error".equals(stringValue(processed.get("type")))) {
            return new ProcessResult(processed, false);
        }

        String candidate = canonicalCandidate(args, processed, state);
        if (StringUtils.isBlank(candidate)) {
            return new ProcessResult(processed, false);
        }

        if (!isRouteOnly(toolName, args)) {
            if (DrawioCanvasToolNames.OPTIMIZE_DIAGRAM.equals(toolName)) {
                // layout_optimize already owns the accepted layout. Re-running geometry repair here
                // would overwrite its waypoints; only mechanical normalization remains allowed.
                candidate = xmlToolkit.autoRepair(candidate);
            } else {
                // Match the stream writer's deterministic safety pass for create/modify mutations.
                candidate = xmlToolkit.repairGeometryIfNeeded(xmlToolkit.autoRepair(candidate));
            }
        }
        // The post-processor still belongs to the legacy deterministic repair path. Phase 3 will
        // replace this with the routed diagram profile and remove the compatibility analysis.
        CanvasAnalysis analysis = xmlToolkit.analyzeForLegacyRouting(candidate);
        processed.put("analysis", OBJECT_MAPPER.convertValue(
                DrawioCanvasMcpService.CanvasAnalysisResponse.from(analysis), MAP_TYPE));
        processed.put("repairBrief", DrawioRepairBriefComposer.compose(analysis));
        if (state != null) {
            state.put(DRAFT_DIAGRAM_STATE_KEY, candidate);
        }
        return new ProcessResult(processed, true);
    }

    private boolean isRouteOnly(String toolName, Map<String, Object> args) {
        return DrawioCanvasToolNames.OPTIMIZE_DIAGRAM.equals(toolName)
                && args != null
                && "route_only".equals(stringValue(args.get("mode")).trim());
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

    /** Separates an acknowledged payload type from a mutation that was actually canonicalized. */
    public record ProcessResult(Map<String, Object> response, boolean mutationApplied) {
    }
}
