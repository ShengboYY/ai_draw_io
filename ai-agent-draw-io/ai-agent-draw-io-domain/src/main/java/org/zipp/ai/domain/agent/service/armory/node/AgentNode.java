package org.zipp.ai.domain.agent.service.armory.node;

import org.zipp.ai.domain.agent.model.entity.ArmoryCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import org.zipp.ai.domain.agent.service.armory.AbstractArmorySupport;
import org.zipp.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.zipp.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import org.zipp.ai.domain.agent.service.armory.matter.tool.SpringToolCallbackAdkTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.tools.BaseTool;
import com.google.adk.agents.LlmAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        List<BaseTool> adkTools = adkTools(dynamicContext);

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());
            List<BaseTool> agentTools = adkToolsFor(agentConfig, adkTools);
            if (!agentTools.isEmpty()) {
                builder.tools(agentTools);
            }
            LlmAgent llmAgent = builder.build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

    private List<BaseTool> adkTools(DefaultArmoryFactory.DynamicContext dynamicContext) {
        List<ToolCallback> callbacks = dynamicContext.getValue(ChatModelNode.TOOL_CALLBACKS_CONTEXT_KEY);
        if (callbacks == null) {
            return Collections.emptyList();
        }
        return SpringToolCallbackAdkTool.fromCallbacks(callbacks);
    }

    private List<BaseTool> adkToolsFor(AiAgentConfigTableVO.Module.Agent agentConfig, List<BaseTool> adkTools) {
        List<String> allowedTools = agentConfig.getAllowedTools();
        if (allowedTools == null) {
            return adkTools;
        }
        if (allowedTools.isEmpty() || adkTools.isEmpty()) {
            return Collections.emptyList();
        }

        // Agent-level allowlists physically remove tools from that ADK agent's declarations.
        Set<String> allowed = new LinkedHashSet<>(allowedTools);
        return adkTools.stream()
                .filter(tool -> allowed.contains(tool.name()))
                .toList();
    }

}
