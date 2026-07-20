package org.zipp.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.infrastructure.adapter.port.UuidCatalogIdFactory;

/** Shared opaque identity generation for material bounded-context services. */
@Configuration
public class MaterialIdentityConfig {
    @Bean
    public CatalogIdFactory catalogIdFactory() {
        return new UuidCatalogIdFactory();
    }
}
