package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 信用证审单不符点与拒付决策服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库并自动建表与写入合成种子数据，无需外部 MySQL 或银行系统。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地单证服务；可通过 DB_URL/DB_USER/DB_PASSWORD/DB_DRIVER 环境变量切换到外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
