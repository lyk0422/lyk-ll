package com.example.starter.forwarder.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 转发器配置：绑定 forwarder.* 属性，并显式执行 classpath:schema.sql 建表。
 *
 * <p>脚本全部使用 IF NOT EXISTS，重启安全，文件库支持跨进程恢复；
 * 由 Spring 生命周期保证每个上下文只执行一次。</p>
 */
@Configuration
@EnableConfigurationProperties(ForwarderProperties.class)
public class ForwarderConfig {

    @Bean
    public DataSourceInitializer forwarderDataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
