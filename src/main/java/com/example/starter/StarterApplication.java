package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 出口退税申报服务入口。
 *
 * <p>本地默认使用嵌入式 H2（MODE=MySQL），启动时自动执行 schema.sql 建表；
 * 报关单、发票、退税率文库均通过 API 录入合成数据，仅在 JVM 生命周期内保留。</p>
 */
@SpringBootApplication(scanBasePackages = "com.example")
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
