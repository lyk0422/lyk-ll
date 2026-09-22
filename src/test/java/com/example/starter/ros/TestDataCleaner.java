package com.example.starter.ros;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据清理工具:在场景间按外键依赖顺序清空业务表并重置自增 revision。
 */
public final class TestDataCleaner {

    private TestDataCleaner() {
    }

    public static void cleanAll(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM node_resource");
        jdbcTemplate.update("DELETE FROM service_claim");
        jdbcTemplate.update("DELETE FROM node_event");
        jdbcTemplate.update("DELETE FROM node_checkpoint");
        jdbcTemplate.update("DELETE FROM request_record");
        jdbcTemplate.update("DELETE FROM subscriber_cursor");
        jdbcTemplate.update("DELETE FROM ros_node");
        jdbcTemplate.execute("ALTER TABLE node_event ALTER COLUMN revision RESTART WITH 1");
    }
}
