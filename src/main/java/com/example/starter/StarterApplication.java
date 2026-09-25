package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 渔业捕捞配额与渔捞日志核销服务入口；默认使用嵌入式 H2（MODE=MySQL），启动时自动建表并写入合成数据。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 无需外部数据库即可启动；可用 DB_URL/DB_USER/DB_PASSWORD/DB_DRIVER 覆盖数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
