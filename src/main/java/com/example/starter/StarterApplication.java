package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地温室环控服务入口。默认使用嵌入式 H2（MODE=MySQL）并自动建表，无需外部数据库；
 * 设 {@code -Dgreenhouse.demo-seed=true} 可在启动时写入演示用合成作物与分区。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动 Web 服务；数据仅保留在 JVM 生命周期内。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
