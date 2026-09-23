package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 铁路检修封锁窗审批与冲突协调服务入口。
 *
 * <p>默认使用嵌入式 H2 内存库（MODE=MySQL），无需外部数据库；
 * 可通过 DB_URL/DB_USER/DB_PASSWORD 环境变量覆盖数据源配置。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地封锁窗服务（UTC 时间，数据仅保留在 JVM 生命周期内）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
