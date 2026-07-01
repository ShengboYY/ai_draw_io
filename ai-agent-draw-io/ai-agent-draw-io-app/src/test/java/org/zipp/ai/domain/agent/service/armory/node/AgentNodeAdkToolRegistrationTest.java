package org.zipp.ai.domain.agent.service.armory.node;

import com.google.adk.agents.LlmAgent;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.zipp.ai.domain.agent.model.entity.ArmoryCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import org.zipp.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentNodeAdkToolRegistrationTest {

    @Test
    public void shouldRegisterSpringToolCallbacksOnAdkLlmAgent() throws Exception {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new DrawioCanvasMcpService())
                .build()
                .getToolCallbacks();
        DefaultArmoryFactory.DynamicContext context = new DefaultArmoryFactory.DynamicContext();
        context.setChatModel(new FakeChatModel());
        context.setValue(ChatModelNode.TOOL_CALLBACKS_CONTEXT_KEY, List.of(callbacks));

        TestableAgentNode node = new TestableAgentNode();
        node.applyForTest(commandWithOneAgent(), context);

        LlmAgent agent = (LlmAgent) context.getAgentGroup().get("agent_drawer");
        assertTrue(agent.tools().stream().anyMatch(tool -> "create_diagram".equals(tool.name())));
        assertTrue(agent.tools().stream().anyMatch(tool -> "modify_diagram".equals(tool.name())));
        assertTrue(agent.tools().stream().anyMatch(tool -> "optimize_diagram".equals(tool.name())));
        assertFalse(agent.tools().stream().anyMatch(tool -> "inspect_canvas".equals(tool.name())));
        assertFalse(agent.tools().stream().anyMatch(tool -> "display_diagram".equals(tool.name())));
        assertFalse(agent.tools().stream().anyMatch(tool -> "route_edges".equals(tool.name())));
        assertTrue(agent.tools().stream()
                .filter(tool -> "create_diagram".equals(tool.name()))
                .findFirst()
                .flatMap(tool -> tool.declaration())
                .flatMap(declaration -> declaration.name())
                .filter("create_diagram"::equals)
                .isPresent());
        assertTrue(agent.tools().stream()
                .filter(tool -> "create_diagram".equals(tool.name()))
                .findFirst()
                .flatMap(tool -> tool.declaration())
                .flatMap(declaration -> declaration.parameters())
                .isPresent());
    }

    private ArmoryCommandEntity commandWithOneAgent() {
        AiAgentConfigTableVO config = new AiAgentConfigTableVO();
        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        AiAgentConfigTableVO.Module.Agent agent = new AiAgentConfigTableVO.Module.Agent();
        agent.setName("agent_drawer");
        agent.setDescription("Draws diagrams");
        agent.setInstruction("Use draw.io tools");
        agent.setOutputKey("draft_diagram");
        module.setAgents(List.of(agent));
        config.setModule(module);
        return ArmoryCommandEntity.builder().aiAgentConfigTableVO(config).build();
    }

    private static class TestableAgentNode extends AgentNode {
        private void applyForTest(ArmoryCommandEntity command, DefaultArmoryFactory.DynamicContext context) throws Exception {
            doApply(command, context);
        }

        @Override
        public AiAgentRegisterVO router(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
            return null;
        }
    }

    private static class FakeChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }
    }
}
