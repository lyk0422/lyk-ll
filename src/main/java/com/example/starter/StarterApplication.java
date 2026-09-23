package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地频道播出编排服务入口：版本化播单、直播插播与 as-run 播出日志。
 * 默认使用嵌入式 H2 内存库（MODE=MySQL）与合成时钟，不连接真实广电设备。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动 Web 服务；可通过 DB_URL/DB_USER 环境变量覆盖数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
