package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务工程入口：电梯困人救援、维保超期联动与维保履约续派服务。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库，schema.sql/data.sql 自动初始化，仅用于合成演示。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动 Web 服务；数据库连接由 application.yaml（可用 DB_URL/DB_USER/DB_PASS 覆盖）提供。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
