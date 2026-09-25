package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集中供热二网水力平衡调节服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL）启动，自动建表并初始化合成换热站数据；
 * 也可通过 DB_URL/DB_DRIVER/DB_USER/DB_PASSWORD 切换到外部数据库。</p>
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动水力平衡调节 Web 服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
