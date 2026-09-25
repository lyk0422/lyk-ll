package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地种子批次田间检验与认证分级服务入口。
 *
 * <p>默认使用嵌入式 H2 内存库（MODE=MySQL），无需外部 MySQL、Docker 或检测机构；
 * 数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
