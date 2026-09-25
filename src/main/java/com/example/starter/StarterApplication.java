package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 保税货物区间转关与核销服务入口。默认使用嵌入式 H2（MySQL 兼容模式），无需外部数据库。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    /**
     * 启动服务；默认连接嵌入式 H2 内存库，可通过 DB_URL/DB_USER/DB_PASSWORD/DB_DRIVER 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
