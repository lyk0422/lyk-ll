package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务题目工程入口：配网故障抢修排序与重要用户保电服务。
 * 默认使用嵌入式 H2（MODE=MySQL），无需外部 MySQL/Docker。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
