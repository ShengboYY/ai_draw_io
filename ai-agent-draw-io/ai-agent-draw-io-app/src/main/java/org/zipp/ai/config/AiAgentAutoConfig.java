package org.zipp.ai.config;

import org.zipp.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.zipp.ai.domain.agent.service.IArmoryService;
import org.zipp.ai.types.util.SecretLogSanitizer;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.ArrayList;

@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            var agentConfigs = aiAgentAutoConfigProperties.getTables().values();
            log.info("Ai Agent 智能体装配: loaded {} agents", agentConfigs.size());
            if (log.isDebugEnabled()) {
                // Full agent instructions are useful for deep debugging, but too noisy and sensitive for normal startup logs.
                log.debug("Ai Agent 智能体装配详情 {}", SecretLogSanitizer.sanitize(JSON.toJSONString(agentConfigs)));
            }

            armoryService.acceptArmoryAgents(new ArrayList<>(agentConfigs));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
