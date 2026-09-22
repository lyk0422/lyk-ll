package com.example.starter.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试间清理 H2 数据并重置确定性轨迹序号，保证用例相互独立、无顺序依赖。
 */
@Component
public class TestDataCleaner {

    private final JdbcTemplate jdbc;

    public TestDataCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.execute("DELETE FROM track_evidence");
        jdbc.execute("DELETE FROM track_event");
        jdbc.execute("DELETE FROM processed_detection");
        jdbc.execute("DELETE FROM track");
        jdbc.update("UPDATE track_seq SET next_val = 1 WHERE id = 1");
    }
}
