package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 媒体版权窗口与播出授权校验服务入口；默认以嵌入式 H2（MODE=MySQL）启动，详见
 * {@code README-media-rights.md}。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地媒体版权窗口 API；未配置 DB_URL 时使用 H2 内存库并自动建表。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
