package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：感知目标跟踪与轨迹状态机服务。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库自动建表启动，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动轨迹关联与状态机 Web 服务；数据源可通过 DB_URL/DB_USER/DB_PASSWORD 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
