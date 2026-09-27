package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供列车制动机试验、摘挂核对与合成列尾装置控制的 Web 服务。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库，自动初始化建表，数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
