package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * 多传感器时间同步与数据配对服务入口。默认使用嵌入式 H2（MySQL 兼容模式），
 * 本地运行与测试不依赖外部数据库。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StarterApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 系统时钟 Bean，便于测试替换为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
