package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地港区靠泊窗口服务入口；默认使用嵌入式 H2（MODE=MySQL），无需外部数据库。
 */
@EnableScheduling
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务，默认连接嵌入式 H2 内存库并自动建表、初始化合成数据。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
