package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.client.impl.SSEToolMcpCreateService;

import static org.junit.Assert.assertEquals;

public class SSEToolMcpCreateServiceTest {

    @Test
    public void shouldSkipUnavailableSseMcpInsteadOfThrowing() throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName("broken-search");
        sse.setBaseUri("not-a-valid-url");
        toolMcp.setSse(sse);

        ToolCallback[] callbacks = new SSEToolMcpCreateService().buildToolCallback(toolMcp);

        assertEquals(0, callbacks.length);
    }
}
