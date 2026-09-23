package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * micro-ROS 边缘设备离线消息转发器入口。
 * 默认使用工作目录下的嵌入式 H2 文件库（MODE=MySQL），无需外部数据库。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    /**
     * 启动边缘转发服务；可用 DB_URL 覆盖数据源指向外部 MySQL。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
