package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 多传感器时间同步与数据配对服务入口。
 *
 * <p>提供 LiDAR/Radar/Camera/IMU 消息摄取、水位线维护、漂移校正热更新、
 * 融合帧构建与 H2 事件重建能力；默认使用嵌入式 H2，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务；默认连接嵌入式 H2 内存库并自动建表。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
