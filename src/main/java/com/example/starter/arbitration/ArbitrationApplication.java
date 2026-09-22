package com.example.starter.arbitration;

import com.example.starter.arbitration.config.ArbitrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ROS 2 Control 风格指令仲裁与安全降级服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL），无需外部 MySQL/Docker；数据仅在 JVM 生命周期内保留。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ArbitrationProperties.class)
public class ArbitrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArbitrationApplication.class, args);
    }
}
