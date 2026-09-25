package com.example.starter.deicing.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 仅在嵌入式 H2（MODE=MySQL）下自动执行 schema.sql 建表并写入合成数据；
 * 历史 compose/MySQL 入口的数据源不受本初始化器影响。
 */
@Configuration(proxyBeanMethods = false)
public class H2SchemaInitConfig {

    /**
     * 数据源 URL 指向 H2 时生效。
     */
    static class H2UrlCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("spring.datasource.url");
            return url != null && url.startsWith("jdbc:h2:");
        }
    }

    @Bean
    @Conditional(H2UrlCondition.class)
    public DataSourceInitializer h2DataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(false);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
