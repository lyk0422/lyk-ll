package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ATM 加钞计划与双人押运核销服务入口。
 * 默认使用嵌入式 H2（MySQL 兼容模式）并自动建表、写入合成 ATM/押运员数据，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 本地直接启动；可用 DB_URL/DB_USER 环境变量覆盖为外部数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
