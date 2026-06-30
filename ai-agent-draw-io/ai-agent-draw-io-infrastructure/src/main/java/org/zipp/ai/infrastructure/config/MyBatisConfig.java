package org.zipp.ai.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** Registers the MyBatis mapper interfaces under the infrastructure dao package. */
@Configuration
@MapperScan("org.zipp.ai.infrastructure.dao")
public class MyBatisConfig {
}
