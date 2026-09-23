package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供 ROS 参数风格配置中心（Schema、事务式热更新、静态参数保护与事件续传），
 * 默认使用 H2 内存库，无需外部数据库、Docker 或真实 ROS。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动配置中心服务；未设置 DB_URL/DB_USER 时默认连接本地 H2 内存库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
