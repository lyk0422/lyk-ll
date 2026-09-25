package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 血液成分检疫隔离与放行服务入口。
 *
 * <p>本地默认使用嵌入式 H2（MySQL 兼容模式）并自动建表，无需外部 MySQL/Docker；
 * 可通过 DB_URL/DB_USER/DB_PASSWORD 环境变量切换到外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
