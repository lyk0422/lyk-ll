package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：提供 Nav2 风格动态 Costmap REST 服务（静态格栅、动态障碍物、
 * 膨胀层、TTL 清理、版本快照与恢复）；默认使用嵌入式 H2（MODE=MySQL），无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 以默认嵌入式 H2（MySQL 兼容模式）或 DB_URL 指定的外部数据库启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
