package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 传染病直报本地服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库，自动建表并初始化合成病种/机构目录，
 * 无需 MySQL、Docker 或外部直报平台；数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
