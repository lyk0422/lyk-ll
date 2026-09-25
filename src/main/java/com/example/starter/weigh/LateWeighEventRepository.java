package com.example.starter.weigh;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 迟到过磅事件数据访问：结案后到达的过磅数据仅登记备查，不改判已结案件。
 */
@Repository
public class LateWeighEventRepository {

    private final JdbcTemplate jdbc;

    public LateWeighEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long caseId, long weighRecordId, String plateNo, String stationId, Instant weighedAt) {
        jdbc.update("INSERT INTO late_weigh_event (case_id, weigh_record_id, plate_no, station_id, weighed_at, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                caseId, weighRecordId, plateNo, stationId,
                Timestamp.from(weighedAt), Timestamp.from(Instant.now()));
    }

    public long countByCaseId(long caseId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM late_weigh_event WHERE case_id = ?",
                Long.class, caseId);
    }
}
