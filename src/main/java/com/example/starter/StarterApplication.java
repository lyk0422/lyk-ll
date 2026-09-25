package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 航空装配力矩紧固与见证点控制服务入口。
 *
 * <p>本地默认使用嵌入式 H2（MODE=MySQL）内存库并自动建表，无需 MySQL/Docker：
 * 数据仅保留在 JVM 生命周期内。设置 DB_URL/DB_USER 环境变量可切换至外部 MySQL 入口
 * （该入口下需将 SQL_INIT_MODE 设为 never，表结构由外部库管理）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动力矩紧固与见证点控制 HTTP 服务（默认端口 8080）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
