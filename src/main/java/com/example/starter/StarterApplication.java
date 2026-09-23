package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；默认使用嵌入式 H2（MODE=MySQL）内存索引与本地块文件目录，
 * 提供 LiDAR 点云块写入、空间查询、损坏隔离与索引重建服务，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动点云服务；可用 DB_URL/DB_USER 等环境变量覆盖数据源配置。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
