package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 群塔防碰撞区域授权与越限处置服务入口。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 本地默认使用嵌入式 H2（MODE=MySQL）启动，无需外部数据库；亦可用 DB_URL/DB_USER 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
