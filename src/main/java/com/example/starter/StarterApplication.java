package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 业务题目工程入口；提供 Web 服务、H2 内存库（MODE=MySQL）自动初始化与列尾装置核对业务。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 全系统一 UTC 时钟；列尾自检有效期按 UTC 判定（now &lt; 有效截止，恰达失效），测试可替换为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
