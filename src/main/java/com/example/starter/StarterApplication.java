package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * micro-ROS 边缘设备离线消息转发器入口；默认使用工作目录下的嵌入式 H2 文件库
 * （MODE=MySQL），无需 Docker 或外部数据库即可本地启动。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动转发服务；数据源默认 ./data/forwarder（H2 文件库），可用 DB_URL 等环境变量覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
