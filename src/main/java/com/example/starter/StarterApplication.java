package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 航空装配力矩紧固与见证点控制服务入口。
 *
 * <p>本地默认使用嵌入式 H2（MODE=MySQL），启动时自动建表，无需外部 MySQL/Docker。
 * 业务实现见 {@code com.example.starter.torque} 包。</p>
 */
@SpringBootApplication
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
