package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 城镇燃气门站加臭与压力联动服务入口。
 *
 * <p>本地默认 local profile，使用嵌入式 H2（MODE=MySQL）自动建表与合成种子数据，
 * 无需外部 MySQL/Docker；业务接口见
 * {@link com.example.starter.gas.web.GasStationController}。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动门站服务；默认监听 8080 端口。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
