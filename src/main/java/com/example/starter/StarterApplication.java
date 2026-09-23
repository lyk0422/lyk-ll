package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 业务题目工程入口；提供受限 Xacro/URDF 编译与依赖感知缓存服务。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StarterApplication {

    /**
     * 启动本地机器人描述编译服务；默认使用开启 MySQL 兼容模式的内嵌 H2，无需外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
