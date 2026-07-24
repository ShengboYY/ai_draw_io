package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.chartbook.service.DefaultChartbookFileModule;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;

import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class MaterialCatalogConfig {
    @Bean
    public MaterialCatalogService materialCatalogService(MaterialCatalogPort catalog,
                                                         CatalogIdFactory ids) {
        return new MaterialCatalogService(catalog, ids, new MaterialScopePolicy());
    }

    @Bean
    public ChartbookFileModule chartbookFileModule(ChartbookCatalogPort chartbooks,
                                                   MaterialCatalogService materials,
                                                   MaterialLifecycleService lifecycle) {
        return new DefaultChartbookFileModule(chartbooks, materials, lifecycle);
    }

    @Bean
    public ChartbookCatalogService chartbookCatalogService(ChartbookCatalogPort chartbooks,
                                                           MaterialCatalogService materials,
                                                           ChartbookFileModule files,
                                                           CatalogIdFactory ids,
                                                           ObjectProvider<Clock> clocks) {
        return new ChartbookCatalogService(chartbooks, materials, files, ids,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
