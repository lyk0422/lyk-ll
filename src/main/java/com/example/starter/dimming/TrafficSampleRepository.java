package com.example.starter.dimming;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成检测采样数据访问：车流量按5分钟滚动窗口统计。
 */
@Repository
public class TrafficSampleRepository {

    private final JdbcTemplate jdbc;

    public TrafficSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String circuitCode, double brightness, int trafficFlow, LocalDateTime recordedAt) {
        jdbc.update(
                "INSERT INTO traffic_sample (circuit_code, brightness, traffic_flow, recorded_at) VALUES (?,?,?,?)",
                circuitCode, brightness, trafficFlow, recordedAt);
    }

    /** 滚动窗口 [from, to] 内的车流量样本 */
    public List<Integer> findTrafficBetween(String circuitCode, LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT traffic_flow FROM traffic_sample WHERE circuit_code = ?"
                        + " AND recorded_at >= ? AND recorded_at <= ?",
                (rs, rowNum) -> rs.getInt(1), circuitCode, from, to);
    }
}
