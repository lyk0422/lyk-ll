package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 卫星测控过境窗口编排与冲突消解服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL）并自动建表、初始化合成数据，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地测控窗口编排服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
