package org.zipp.ai.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
@ConditionalOnProperty(value = "account.email.sender", havingValue = "ses")
public class SesEmailConfig {

    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client(@Value("${account.email.region}") String region) {
        return SesV2Client.builder()
                .region(Region.of(region))
                .build();
    }
}
