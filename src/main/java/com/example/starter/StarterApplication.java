package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地充电漫游服务入口：默认使用嵌入式 H2（MODE=MySQL），启动时自动建表并写入合成数据，
 * 提供即插即充授权、会话启停、计量上传、跨网清分与差异挂账核销 API。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 无需外部数据库即可启动；如需外部 MySQL，通过 DB_URL/DB_USER/DB_PASSWORD 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
