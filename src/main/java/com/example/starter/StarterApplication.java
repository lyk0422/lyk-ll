package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 融资融券追保与强平阶梯服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库，自动建表，无需外部 MySQL、Docker 或行情系统。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地信用账户风控服务（默认 H2 内存库；可用 DB_URL/DB_USER/DB_PASSWORD/DB_DRIVER 覆盖）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
