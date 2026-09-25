package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 生猪屠宰同步检疫与无害化处理服务入口。
 *
 * <p>本地默认使用嵌入式 H2（{@code MODE=MySQL}），无需 MySQL、Docker 或外部系统，
 * 数据仅在 JVM 生命周期内保留；建表脚本 {@code classpath:schema.sql} 启动时自动执行。
 * 可通过 {@code DB_URL}/{@code DB_USER}/{@code DB_PASSWORD} 覆盖为外部数据库。
 *
 * <p>本地启动：{@code ./gradlew bootRun}，或 {@code java -jar build/libs/app.jar}。
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
