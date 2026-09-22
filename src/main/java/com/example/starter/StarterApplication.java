package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CAN Bus DBC 增量解析服务入口。
 *
 * <p>提供 DBC 版本原子导入、按版本解析 CAN 帧及信号最新状态查询；
 * 默认使用嵌入式 H2（MODE=MySQL），无需外部数据库，数据仅保留在当前 JVM 生命周期内。</p>
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动 Web 服务；建表脚本 schema.sql 随数据源初始化自动执行。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
