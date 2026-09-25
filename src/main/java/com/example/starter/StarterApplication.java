package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 药物警戒个例管理服务入口；默认使用内嵌 H2（MODE=MySQL）内存库并自动建表，
 * 无需外部 MySQL、Docker 或监管网关；设置 DB_URL/DB_USER 等环境变量仍可切换外部数据源。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地服务；数据仅保留在当前 JVM 生命周期内。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
