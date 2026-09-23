package com.example.starter.trajectory.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地 H2（MODE=MySQL）内存库自动建表。
 * 仅当数据源为 H2 时执行；配置外部 MySQL 时不在此自动建表，保留历史部署入口。
 */
@Component
public class H2SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public H2SchemaInitializer(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:h2:")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rule_config (
                    rule_version       VARCHAR(128) PRIMARY KEY,
                    max_time_gap       DOUBLE NOT NULL,
                    max_speed          DOUBLE NOT NULL,
                    max_acceleration   DOUBLE NOT NULL,
                    max_curvature      DOUBLE NOT NULL,
                    road_tolerance     DOUBLE NOT NULL,
                    collision_distance DOUBLE NOT NULL,
                    status             VARCHAR(16) NOT NULL,
                    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS validation_record (
                    request_id   VARCHAR(128) PRIMARY KEY,
                    rule_version VARCHAR(128) NOT NULL,
                    request_hash CHAR(64) NOT NULL,
                    result_json  CLOB NOT NULL,
                    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("COMMENT ON TABLE rule_config IS '版本化安全规则配置，发布后不可原地修改'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.rule_version IS '规则版本号，全局唯一'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.max_time_gap IS '相邻轨迹点最大时间间隔，单位秒'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.max_speed IS '最大纵向速度，单位米/秒'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.max_acceleration IS '最大纵向加速度绝对值，单位米/秒平方'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.max_curvature IS '最大曲率绝对值，单位1/米'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.road_tolerance IS '道路容差，单位米；边界点合法'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.collision_distance IS '碰撞安全距离，单位米；距离小于等于该值即碰撞风险'");
        jdbcTemplate.execute("COMMENT ON COLUMN rule_config.status IS '配置状态：DRAFT 草稿 / PUBLISHED 已发布'");
        jdbcTemplate.execute("COMMENT ON TABLE validation_record IS '校验请求幂等记录与结果快照'");
        jdbcTemplate.execute("COMMENT ON COLUMN validation_record.request_id IS '幂等键，同键同参返回原结果，同键异参返回409'");
        jdbcTemplate.execute("COMMENT ON COLUMN validation_record.rule_version IS '本次复核使用的规则版本'");
        jdbcTemplate.execute("COMMENT ON COLUMN validation_record.request_hash IS '请求参数（不含requestId）的SHA-256，用于冲突检测'");
        jdbcTemplate.execute("COMMENT ON COLUMN validation_record.result_json IS '校验结果JSON快照'");
    }
}
