package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 水泥窑替代燃料掺烧与排放联动服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL），无需外部 MySQL/Docker；数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
