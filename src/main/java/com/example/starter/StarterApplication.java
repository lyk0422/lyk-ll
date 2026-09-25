package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 碳排放配额清缴与抵消核销服务入口；本地默认使用嵌入式 H2，无需外部数据库。
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地 H2 或环境变量提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
