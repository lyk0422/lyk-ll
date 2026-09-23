package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电子证据保全与安全清除服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL）命名内存库，启动时自动执行 schema.sql 建表，
 * 无需 MySQL、Docker 或对象存储；数据仅保留在 JVM 生命周期内，证据内容均为合成字节。</p>
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 本地启动：./gradlew bootRun（默认 8080 端口，H2 内存库 digital_evidence）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
