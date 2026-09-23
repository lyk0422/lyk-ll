package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口。
 * <p>启动本地 MoveIt 2 Planning Scene 管理服务：默认使用嵌入式 H2 内存库
 * （MODE=MySQL），自动完成建表与合成机器人模型初始化，无需外部
 * MySQL、Docker 或真实 MoveIt 2。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地默认 H2 配置启动服务；也可用 DB_URL/DB_USER/DB_PASSWORD 覆盖数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
