package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 换电站电池资产流转与门槛放行台账服务入口。
 *
 * <p>默认使用嵌入式 H2（MODE=MySQL）内存库并自动建表，无需 MySQL、Docker 或外部换电网络。
 */
@SpringBootApplication
public class StarterApplication {

    /** 以默认 H2 内存库配置启动服务。 */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
