package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务题目工程入口；启动隧道火灾通风联动服务（H2 内存库，无需外部机电系统）。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    /**
     * 使用本地 H2 内存库（MODE=MySQL）启动服务；亦可通过 DB_URL 指向外部库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
