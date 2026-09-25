package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；默认以嵌入式 H2（MODE=MySQL）启动隧道火灾通风联动服务，
 * 自动建表并初始化合成分区/风机/风阀数据，无需外部数据库或机电系统。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动通风联动服务；可用 DB_URL/DB_USER/DB_PASSWORD 覆盖默认数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
