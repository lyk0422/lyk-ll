package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 取水许可水量分配与计量对冲服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL），无需外部数据库；业务接口见
 * {@code com.example.starter.water.web.WaterController}。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地取水监管服务；未提供 DB_URL 时使用 H2 内存库并自动建表。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
