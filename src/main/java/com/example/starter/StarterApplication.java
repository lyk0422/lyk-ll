package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据中心割接窗口与回退判据服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL）命名内存库，启动时自动建表并写入合成主数据；
 * 本地运行无需 MySQL、Docker 或外部系统，数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动割接服务；监听 8080 端口。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
