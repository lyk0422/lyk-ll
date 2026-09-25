package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 林木采伐许可与迹地更新联动服务入口。
 * 默认使用嵌入式 H2（MySQL 兼容模式），自动建表并写入合成小班/限额数据，
 * 不依赖外部 MySQL、Docker 或其他系统。
 */
@EnableScheduling
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地林木采伐监管服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
