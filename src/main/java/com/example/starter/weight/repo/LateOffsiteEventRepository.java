package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 结案后迟到非现场数据事件仓储：只记事件，不改已结案件。
 */
@Repository
public class LateOffsiteEventRepository {

    private final JdbcTemplate jdbc;

    public LateOffsiteEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录迟到非现场数据事件。
     */
    public void insert(long offsiteWeighId, long caseId, String plateNo, String laneNo,
                       Instant weighTime, String note, Instant now) {
        jdbc.update("""
                INSERT INTO late_offsite_event
                  (offsite_weigh_id, case_id, plate_no, lane_no, weigh_time, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, offsiteWeighId, caseId, plateNo, laneNo, Timestamp.from(weighTime), note,
                Timestamp.from(now));
    }

    /**
     * 查询案件下的迟到非现场事件（测试核查用）。
     */
    public List<Map<String, Object>> findByCaseId(long caseId) {
        return jdbc.queryForList(
                "SELECT * FROM late_offsite_event WHERE case_id = ? ORDER BY id", caseId);
    }
}
