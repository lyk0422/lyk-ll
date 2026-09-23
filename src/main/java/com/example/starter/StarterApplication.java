package com.example.starter;

import com.example.starter.config.DataDirInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * micro-ROS 边缘设备离线消息转发器入口。
 * 默认使用工作目录下嵌入式 H2 文件库（MODE=MySQL），无需外部数据库。
 */
@SpringBootApplication
@EnableScheduling
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StarterApplication.class);
        // 在数据源创建前确保 H2 文件库目录存在。
        application.addListeners(new DataDirInitializer());
        application.run(args);
    }
}
