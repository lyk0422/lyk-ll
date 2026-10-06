package com.example.starter.trace.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 蜂场预警的数据访问。
 */
@Repository
public class WarningRepository {

    private final JdbcTemplate jdbc;

    public WarningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试写入一条蜂场预警；同蜂场同花期已存在预警时忽略（唯一约束兜底）。
     */
    public void insertIfAbsent(long apiaryId, String flowerPeriod, int suspectedCount, LocalDateTime createdAt) {
        try {
            jdbc.update(
                    "INSERT INTO apiary_warning (apiary_id, flower_period, suspected_count, created_at) VALUES (?, ?, ?, ?)",
                    apiaryId, flowerPeriod, suspectedCount, Timestamp.valueOf(createdAt));
        } catch (DuplicateKeyException e) {
            // 同蜂场同花期只预警一次
        }
    }

    /**
     * 查询全部蜂场预警，按 ID 升序。
     */
    public List<WarningRow> findAll() {
        return jdbc.query(
                "SELECT w.id, w.apiary_id, a.code, a.name, w.flower_period, w.suspected_count, w.created_at"
                        + " FROM apiary_warning w JOIN apiary a ON a.id = w.apiary_id ORDER BY w.id",
                (rs, i) -> new WarningRow(rs.getLong("id"), rs.getLong("apiary_id"),
                        rs.getString("code"), rs.getString("name"), rs.getString("flower_period"),
                        rs.getInt("suspected_count"), rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /**
     * 蜂场预警行记录。
     */
    public record WarningRow(Long id, Long apiaryId, String apiaryCode, String apiaryName,
                             String flowerPeriod, Integer suspectedCount, LocalDateTime createdAt) {
    }
}
