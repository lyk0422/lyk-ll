package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务题目工程入口；提供闸门调度令执行与回执服务，本地默认使用 H2 内存库。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    /**
     * 启动本地闸门调度服务（无需外部数据库或监控系统）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
