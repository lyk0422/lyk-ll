package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 业务题目工程入口，提供刷新令牌轮换与重放遏制本地 API。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StarterApplication {

    /**
     * 启动本地服务；默认使用嵌入式 H2（MySQL 兼容模式），无需外部数据库或身份提供商。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
