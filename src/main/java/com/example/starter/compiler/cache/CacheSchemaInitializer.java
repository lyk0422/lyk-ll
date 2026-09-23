package com.example.starter.compiler.cache;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时在内存库中创建编译缓存表（幂等，中文 COMMENT 与字段语义同步）。
 */
@Component
@Order(0)
public class CacheSchemaInitializer implements ApplicationRunner {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS robot_compile_cache (
                cache_key         VARCHAR(64)  PRIMARY KEY COMMENT '缓存键：入口路径、全部传递依赖内容摘要与规范化参数的聚合 SHA-256',
                urdf              CLOB         NOT NULL    COMMENT '规范 URDF 文本（UTF-8 确定性序列化）',
                urdf_sha256       VARCHAR(64)  NOT NULL    COMMENT '规范 URDF 字节的 SHA-256（十六进制）',
                dependencies_json CLOB         NOT NULL    COMMENT '全部传递依赖清单 JSON（相对路径+原始内容摘要）',
                chain_json        CLOB         NOT NULL    COMMENT 'include 展开链路 JSON（入口在首）'
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public CacheSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
    }
}
