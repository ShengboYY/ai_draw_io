package org.zipp.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
// Keep mapper scanning on the boot entrypoint so IDE runs register DAO interfaces.
@MapperScan("org.zipp.ai.infrastructure.dao")
@Configurable
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class);
    }

    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }

    @Bean("drawioCanvasToolCallbackProvider")
    public ToolCallbackProvider drawioCanvasTools(DrawioCanvasMcpService drawioCanvasMcpService) {
        return MethodToolCallbackProvider.builder().toolObjects(drawioCanvasMcpService).build();
    }

    @Bean("drawioSkillToolCallbackProvider")
    public ToolCallbackProvider drawioSkillTools(DrawioSkillMcpService drawioSkillMcpService) {
        return MethodToolCallbackProvider.builder().toolObjects(drawioSkillMcpService).build();
    }

}
