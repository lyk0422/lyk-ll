package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 临床试验随机入组与应急揭盲控制服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL）内存库，启动时自动建表并写入合成授权用户，
 * 无需外部 MySQL、Docker 或随机化系统；数据仅在 JVM 生命周期内保留。
 * 如需外部库，可通过 DB_URL/DB_USER/DB_PASSWORD/DB_DRIVER 环境变量覆盖。</p>
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动随机入组与揭盲 HTTP 服务（默认端口 8080）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
