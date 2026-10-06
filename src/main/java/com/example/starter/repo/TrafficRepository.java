package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 车流量合成检测数据访问，按 5 分钟滚动更新。
 */
@Repository
public class TrafficRepository {

    private final JdbcTemplate jdbc;

    public TrafficRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入一条车流量采样。 */
    public void insert(long circuitId, int flow, Instant sampledAt, Instant createdAt) {
        jdbc.update("INSERT INTO traffic_sample (circuit_id, flow, sampled_at, created_at) VALUES (?, ?, ?, ?)",
                circuitId, flow, Timestamp.from(sampledAt), Timestamp.from(createdAt));
    }

    /** 统计回路采样数（测试核验用）。 */
    public int countByCircuit(long circuitId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traffic_sample WHERE circuit_id = ?", Integer.class, circuitId);
        return count == null ? 0 : count;
    }
}
